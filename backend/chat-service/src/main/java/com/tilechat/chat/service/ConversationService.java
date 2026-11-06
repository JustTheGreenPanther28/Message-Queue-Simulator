package com.tilechat.chat.service;

import com.tilechat.chat.domain.Conversation;
import com.tilechat.chat.domain.ConversationMember;
import com.tilechat.chat.domain.MemberStatus;
import com.tilechat.chat.dto.ConversationDto;
import com.tilechat.chat.dto.Requests;
import com.tilechat.chat.realtime.RealtimeEvents.ConversationChanged;
import com.tilechat.chat.repository.ConversationMemberRepository;
import com.tilechat.chat.repository.ConversationRepository;
import com.tilechat.chat.repository.MessageReceiptRepository;
import com.tilechat.chat.repository.UserBlockRepository;
import com.tilechat.common.security.AuthUser;
import com.tilechat.common.web.ApiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * Write side for conversations, including message requests and group invites.
 *
 * <ul>
 *   <li>Starting a direct chat makes it a <b>request</b> for the other person (PENDING) until they
 *       accept. Opening the chat yourself from search counts as accepting.</li>
 *   <li>Creating a group or adding people sends <b>invites</b> (PENDING) to everyone but you.</li>
 *   <li>If the other person has <b>blocked</b> you, their copy is created hidden: you see a normal
 *       pending request, they never see anything (silent block).</li>
 * </ul>
 * Creation is idempotent (unique direct_key; group Idempotency-Key). Membership changes lock the
 * conversation row, so they serialise with message sends.
 */
@Service
public class ConversationService {

    private final TransactionTemplate tx;
    private final ConversationRepository conversations;
    private final ConversationMemberRepository members;
    private final MessageReceiptRepository receipts;
    private final UserBlockRepository blocks;
    private final ReceiptService receiptService;
    private final ConversationViews views;
    private final UserDirectory directory;
    private final MemberCache memberCache;
    private final ChatEventPublisher chatEvents;
    private final ApplicationEventPublisher events;

    public ConversationService(TransactionTemplate tx, ConversationRepository conversations,
                               ConversationMemberRepository members, MessageReceiptRepository receipts,
                               UserBlockRepository blocks, ReceiptService receiptService, ConversationViews views,
                               UserDirectory directory, MemberCache memberCache, ChatEventPublisher chatEvents,
                               ApplicationEventPublisher events) {
        this.tx = tx;
        this.conversations = conversations;
        this.members = members;
        this.receipts = receipts;
        this.blocks = blocks;
        this.receiptService = receiptService;
        this.views = views;
        this.directory = directory;
        this.memberCache = memberCache;
        this.chatEvents = chatEvents;
        this.events = events;
    }

    // ------------------------------------------------------------------ direct chats

    public ConversationDto openDirect(AuthUser me, Long otherId) {
        if (otherId.equals(me.id())) throw ApiException.badRequest("You can't start a chat with yourself");
        directory.requireExisting(List.of(otherId));
        String key = Conversation.directKey(me.id(), otherId);

        Optional<Conversation> existing = conversations.findByDirectKey(key);
        if (existing.isEmpty()) {
            try {
                tx.executeWithoutResult(s -> {
                    Conversation c = conversations.save(Conversation.direct(me.id(), otherId, me.id()));
                    boolean silenced = blocks.existsByBlockerIdAndBlockedId(otherId, me.id());
                    members.saveAll(List.of(ConversationMember.owner(c.getId(), me.id()),
                            ConversationMember.invited(c.getId(), otherId, me.id(), silenced)));
                    members.flush();
                    push(c.getId(), silenced ? List.of(me.id()) : List.of(me.id(), otherId), List.of());
                });
                return views.view(conversations.findByDirectKey(key).orElseThrow().getId(), me.id());
            } catch (DataIntegrityViolationException race) {
                // The other person opened the same chat at the same moment; fall through and join theirs.
            }
        }
        Long id = conversations.findByDirectKey(key).orElseThrow().getId();
        // Opening a chat yourself means you want it: an incoming request becomes accepted.
        tx.executeWithoutResult(s -> {
            conversations.lockById(id);
            ConversationMember mine = members.findByConversationIdAndUserId(id, me.id()).orElseThrow();
            if (mine.getStatus() != MemberStatus.ACCEPTED || mine.isHidden() || mine.isDeleted()) {
                mine.accept();
                memberCache.evictAfterCommit(id);
                push(id, List.of(me.id()), List.of());
            }
        });
        return views.view(id, me.id());
    }

    // ------------------------------------------------------------------ groups

    public ConversationDto createGroup(AuthUser me, Requests.CreateGroup req, String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        if (key != null) {
            Optional<Conversation> existing = conversations.findByCreatedByAndCreationKey(me.id(), key);
            if (existing.isPresent()) return views.view(existing.get().getId(), me.id());
        }
        List<Long> others = new ArrayList<>(new LinkedHashSet<>(req.memberIds()));
        others.remove(me.id());
        if (others.isEmpty()) throw ApiException.badRequest("Pick at least one other member");
        directory.requireExisting(others);

        Long id;
        try {
            id = tx.execute(s -> {
                Conversation c = conversations.save(Conversation.group(req.name().strip(), me.id(), key));
                members.save(ConversationMember.owner(c.getId(), me.id()));
                List<Long> invited = invite(c, me, others);
                members.flush();
                List<Long> audience = new ArrayList<>(List.of(me.id()));
                audience.addAll(invited);
                push(c.getId(), audience, List.of());
                return c.getId();
            });
        } catch (DataIntegrityViolationException race) {
            if (key == null) throw race;
            id = conversations.findByCreatedByAndCreationKey(me.id(), key).orElseThrow(() -> race).getId();
        }
        return views.view(id, me.id());
    }

    /** Invites people who aren't in the group yet. Idempotent: existing members/invitees are skipped. */
    public ConversationDto addMembers(AuthUser me, Long conversationId, Requests.AddMembers req) {
        tx.executeWithoutResult(s -> {
            Conversation c = lockGroup(conversationId);
            List<ConversationMember> rows = members.findByConversationIdOrderById(conversationId);
            boolean accepted = rows.stream().anyMatch(m -> m.getUserId().equals(me.id()) && m.getStatus() == MemberStatus.ACCEPTED);
            if (!accepted) throw ApiException.notFound("Conversation not found");

            List<Long> toAdd = new ArrayList<>(new LinkedHashSet<>(req.userIds()));
            rows.forEach(m -> toAdd.remove(m.getUserId()));
            if (toAdd.isEmpty()) return;
            if (rows.size() + toAdd.size() > 100) throw ApiException.badRequest("A group can have at most 100 members");
            directory.requireExisting(toAdd);

            List<Long> invited = invite(c, me, toAdd);
            members.flush();
            memberCache.evictAfterCommit(conversationId);
            List<Long> audience = new ArrayList<>(rows.stream().filter(ConversationMember::isVisible).map(ConversationMember::getUserId).toList());
            audience.addAll(invited);
            push(conversationId, audience, List.of());
        });
        return views.view(conversationId, me.id());
    }

    /** Creates PENDING invites; returns those who will actually see theirs (not the ones who blocked me). */
    private List<Long> invite(Conversation c, AuthUser me, List<Long> userIds) {
        Set<Long> blockedMe = new HashSet<>(blocks.findWhoBlocked(me.id(), userIds));
        List<Long> visible = new ArrayList<>();
        for (Long uid : userIds) {
            boolean silenced = blockedMe.contains(uid);
            members.save(ConversationMember.invited(c.getId(), uid, me.id(), silenced));
            if (!silenced) visible.add(uid);
        }
        chatEvents.membersAdded(c, directory.actor(me), visible);
        return visible;
    }

    // ------------------------------------------------------------------ answering requests

    /** Idempotent. Accepting a direct chat also marks everything already received as delivered. */
    public ConversationDto accept(AuthUser me, Long conversationId) {
        tx.executeWithoutResult(s -> {
            Conversation c = conversations.lockById(conversationId).orElseThrow(() -> ApiException.notFound("Conversation not found"));
            ConversationMember mine = members.findByConversationIdAndUserId(conversationId, me.id())
                    .filter(ConversationMember::isVisible)
                    .orElseThrow(() -> ApiException.notFound("Conversation not found"));
            if (mine.getStatus() == MemberStatus.ACCEPTED) return;
            mine.accept();
            members.flush();
            memberCache.evictAfterCommit(conversationId);
            if (c.isDirect()) receiptService.markDeliveredIn(me.id(), conversationId);
            push(conversationId, visibleMembers(conversationId), List.of());
        });
        return views.view(conversationId, me.id());
    }

    /**
     * Declines a request or invite. Idempotent. A declined direct chat disappears for you (the
     * sender isn't told); a new message from them makes it a request again. Declining a group
     * invite removes you from the group.
     */
    public void reject(AuthUser me, Long conversationId) {
        tx.executeWithoutResult(s -> {
            Conversation c = conversations.lockById(conversationId).orElseThrow(() -> ApiException.notFound("Conversation not found"));
            Optional<ConversationMember> mine = members.findByConversationIdAndUserId(conversationId, me.id())
                    .filter(ConversationMember::isVisible);
            if (mine.isEmpty()) return;
            if (mine.get().getStatus() == MemberStatus.ACCEPTED) {
                throw ApiException.badRequest(c.isGroup() ? "You're already in this group; leave it instead" : "You already accepted this chat");
            }
            if (c.isDirect()) {
                mine.get().reject();
            } else {
                members.deleteMember(conversationId, me.id());
            }
            members.flush();
            memberCache.evictAfterCommit(conversationId);
            push(conversationId, c.isGroup() ? visibleMembers(conversationId) : List.of(), List.of(me.id()));
        });
    }

    /** Idempotent: leaving a group you're not in is a no-op. */
    public void leave(AuthUser me, Long conversationId) {
        tx.executeWithoutResult(s -> {
            lockGroup(conversationId);
            Optional<ConversationMember> mine = members.findByConversationIdAndUserId(conversationId, me.id());
            if (mine.isEmpty()) return;

            members.deleteMember(conversationId, me.id());
            // Messages I never read must not wait for me forever before they can show "read".
            List<Long> unread = receipts.findAllUnreadIn(me.id(), conversationId);
            receipts.deleteUnread(me.id(), conversationId);
            receiptService.recompute(unread);
            memberCache.evictAfterCommit(conversationId);
            push(conversationId, visibleMembers(conversationId), List.of(me.id()));
        });
    }

    /**
     * "Delete chat", for you only, idempotent. A direct chat leaves your list and its history is
     * cleared for you; unread messages are dropped without telling the sender they were read.
     * It comes back if the other person writes again, or if you open it yourself. A pending
     * request is simply declined. For a group, deleting means leaving it.
     */
    public void deleteChat(AuthUser me, Long conversationId) {
        Conversation c = conversations.findById(conversationId).orElseThrow(() -> ApiException.notFound("Conversation not found"));
        if (c.isGroup()) {
            leave(me, conversationId);
            return;
        }
        Optional<ConversationMember> current = members.findByConversationIdAndUserId(conversationId, me.id());
        if (current.isPresent() && current.get().isVisible() && current.get().getStatus() == MemberStatus.PENDING) {
            reject(me, conversationId);
            return;
        }
        tx.executeWithoutResult(s -> {
            Conversation locked = conversations.lockById(conversationId).orElseThrow();
            Optional<ConversationMember> mine = members.findByConversationIdAndUserId(conversationId, me.id())
                    .filter(ConversationMember::isVisible);
            if (mine.isEmpty()) return;
            mine.get().deleteUpTo(locked.getLastSeq());
            receipts.dismissUnread(me.id(), conversationId);
            members.flush();
            memberCache.evictAfterCommit(conversationId);
            push(conversationId, List.of(), List.of(me.id()));
        });
    }

    // ------------------------------------------------------------------ helpers

    private List<Long> visibleMembers(Long conversationId) {
        return members.findByConversationIdOrderById(conversationId).stream()
                .filter(ConversationMember::isVisible).map(ConversationMember::getUserId).toList();
    }

    /** Pushes (after commit) what each user should now see. */
    private void push(Long conversationId, Collection<Long> audience, Collection<Long> removed) {
        events.publishEvent(new ConversationChanged(conversationId, views.payloads(conversationId, audience), removed));
    }

    private Conversation lockGroup(Long conversationId) {
        Conversation c = conversations.lockById(conversationId)
                .orElseThrow(() -> ApiException.notFound("Conversation not found"));
        if (!c.isGroup()) throw ApiException.badRequest("Only group chats have members to manage");
        return c;
    }

    private static String normalizeKey(String key) {
        if (key == null || key.isBlank()) return null;
        if (key.length() > 64) throw ApiException.badRequest("Idempotency-Key must be at most 64 characters");
        return key.trim();
    }
}
