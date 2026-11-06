package com.tilechat.chat.service;

import com.tilechat.chat.domain.*;
import com.tilechat.chat.dto.MessageDto;
import com.tilechat.chat.dto.Requests;
import com.tilechat.chat.realtime.RealtimeEvents.ConversationChanged;
import com.tilechat.chat.realtime.RealtimeEvents.MessagePosted;
import com.tilechat.chat.repository.*;
import com.tilechat.common.security.AuthUser;
import com.tilechat.common.storage.ObjectStorage;
import com.tilechat.common.storage.PresignedUpload;
import com.tilechat.common.tx.AfterCommit;
import com.tilechat.common.web.ApiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * Sending and reading messages.
 *
 * <p><b>Idempotency.</b> Every send carries a client-generated {@code clientMessageId}.
 * (sender, clientMessageId) is unique in the DB. A retry returns the original message: first
 * via a cheap lookup, then re-checked under the conversation lock, and the unique constraint
 * covers anything left. Images are promoted to a key derived from the upload id, so a retry
 * writes the same object; the tmp upload is deleted only after commit, so a failed attempt
 * can be retried with the same upload.
 *
 * <p><b>Ordering.</b> The conversation row is locked (SELECT ... FOR UPDATE) while the next
 * {@code seq} is assigned, so seq numbers are strictly increasing with no gaps or duplicates.
 *
 * <p><b>Requests and blocks.</b> You can't reply to a request you haven't accepted, and you
 * can't message someone you blocked. If the recipient has blocked you, the message is stored
 * with a suppressed receipt: you see one tick forever, they never see it (silent block). A new
 * message to someone who declined your request turns it back into a request.
 */
@Service
public class MessageService {

    private static final int MAX_PAGE = 100;

    private final TransactionTemplate tx;
    private final ConversationRepository conversations;
    private final ConversationMemberRepository members;
    private final MessageRepository messages;
    private final MessageReceiptRepository receipts;
    private final UserBlockRepository blocks;
    private final MembershipGuard guard;
    private final UserDirectory directory;
    private final ConversationViews views;
    private final MemberCache memberCache;
    private final ChatEventPublisher chatEvents;
    private final ApplicationEventPublisher events;
    private final ObjectStorage storage;
    private final DtoMapper mapper;

    public MessageService(TransactionTemplate tx, ConversationRepository conversations, ConversationMemberRepository members,
                          MessageRepository messages, MessageReceiptRepository receipts, UserBlockRepository blocks,
                          MembershipGuard guard, UserDirectory directory, ConversationViews views, MemberCache memberCache,
                          ChatEventPublisher chatEvents, ApplicationEventPublisher events, ObjectStorage storage, DtoMapper mapper) {
        this.tx = tx;
        this.conversations = conversations;
        this.members = members;
        this.messages = messages;
        this.receipts = receipts;
        this.blocks = blocks;
        this.guard = guard;
        this.directory = directory;
        this.views = views;
        this.memberCache = memberCache;
        this.chatEvents = chatEvents;
        this.events = events;
        this.storage = storage;
        this.mapper = mapper;
    }

    private record Draft(String clientMessageId, MessageType type, String content, String imageKey) {}

    private record WriteResult(MessageDto message, boolean duplicate) {}

    /** Step 1 of sending an image: a presigned URL the browser uploads to directly. */
    public PresignedUpload uploadUrl(AuthUser me, Long conversationId, String contentType, long size) {
        guard.requireAccepted(conversationId, me.id());
        return storage.presignUpload(String.valueOf(me.id()), contentType, size);
    }

    public MessageDto send(AuthUser me, Long conversationId, Requests.SendMessage req) {
        String cmid = req.clientMessageId().trim();
        String text = req.content() == null || req.content().isBlank() ? null : req.content().strip();
        boolean hasUpload = req.uploadKey() != null && !req.uploadKey().isBlank();
        if (text == null && !hasUpload) throw ApiException.badRequest("Message can't be empty");

        Optional<MessageDto> earlier = findDuplicate(me, conversationId, cmid);
        if (earlier.isPresent()) return earlier.get();
        guard.requireAccepted(conversationId, me.id()); // fail fast, before touching storage

        if (!hasUpload) return write(me, conversationId, new Draft(cmid, MessageType.TEXT, text, null));

        String imageKey = storage.promote(req.uploadKey(), String.valueOf(me.id()), "chat/" + conversationId);
        try {
            MessageDto sent = write(me, conversationId, new Draft(cmid, MessageType.IMAGE, text, imageKey));
            storage.deleteQuietly(req.uploadKey());
            return sent;
        } catch (RuntimeException e) {
            storage.deleteQuietly(imageKey); // the upload itself is kept, so the client can simply retry
            throw e;
        }
    }

    private MessageDto write(AuthUser me, Long conversationId, Draft draft) {
        try {
            return tx.execute(s -> writeLocked(me, conversationId, draft)).message();
        } catch (DataIntegrityViolationException race) {
            return findDuplicate(me, conversationId, draft.clientMessageId()).orElseThrow(() -> race);
        }
    }

    private WriteResult writeLocked(AuthUser me, Long conversationId, Draft draft) {
        Conversation c = conversations.lockById(conversationId)
                .orElseThrow(() -> ApiException.notFound("Conversation not found"));

        // Re-check under the lock: a concurrent duplicate that got here first is now visible.
        Optional<MessageDto> earlier = findDuplicate(me, conversationId, draft.clientMessageId());
        if (earlier.isPresent()) return new WriteResult(earlier.get(), true);

        // Fresh from the DB, not the cache: we're holding the lock.
        List<ConversationMember> rows = members.findByConversationIdOrderById(conversationId);
        ConversationMember mine = rows.stream().filter(m -> m.getUserId().equals(me.id())).findFirst()
                .filter(ConversationMember::isVisible)
                .orElseThrow(() -> ApiException.notFound("Conversation not found"));
        if (mine.getStatus() == MemberStatus.PENDING) throw ApiException.forbidden("Accept this request first");

        List<Long> recipients = new ArrayList<>();        // normal receipts, live push, notification
        List<Long> requestRecipients = new ArrayList<>(); // ...for whom this is still a request
        List<Long> silenced = new ArrayList<>();          // blocked me: stored, never shown
        Long reopenedFor = null;

        if (c.isDirect()) {
            ConversationMember other = rows.stream().filter(m -> !m.getUserId().equals(me.id())).findFirst().orElseThrow();
            if (blocks.existsByBlockerIdAndBlockedId(me.id(), other.getUserId())) {
                throw ApiException.forbidden("You blocked this person. Unblock them to send messages.");
            }
            if (other.isHidden() || blocks.existsByBlockerIdAndBlockedId(other.getUserId(), me.id())) {
                silenced.add(other.getUserId());
            } else {
                if (other.getStatus() == MemberStatus.REJECTED) {
                    other.reopenRequest();
                    reopenedFor = other.getUserId();
                }
                if (other.isDeleted()) { // they deleted the chat: it comes back with just the new message
                    other.restore();
                    reopenedFor = other.getUserId();
                }
                recipients.add(other.getUserId());
                if (other.getStatus() == MemberStatus.PENDING) requestRecipients.add(other.getUserId());
            }
        } else {
            rows.stream().filter(m -> !m.getUserId().equals(me.id()) && m.getStatus() == MemberStatus.ACCEPTED && !m.isHidden())
                    .forEach(m -> recipients.add(m.getUserId()));
        }

        Message m = messages.save(new Message(conversationId, c.nextSeq(), me.id(), draft.clientMessageId(),
                draft.type(), draft.content(), draft.imageKey()));
        List<MessageReceipt> rs = new ArrayList<>();
        recipients.forEach(uid -> rs.add(new MessageReceipt(m.getId(), conversationId, uid, false)));
        silenced.forEach(uid -> rs.add(new MessageReceipt(m.getId(), conversationId, uid, true)));
        receipts.saveAll(rs);
        c.recordMessage(m);

        MessageDto dto = mapper.message(m);
        chatEvents.messageCreated(c, m, directory.actor(me), recipients, requestRecipients);
        List<Long> audience = new ArrayList<>(recipients);
        audience.add(me.id());
        events.publishEvent(new MessagePosted(dto, audience));
        if (reopenedFor != null) {
            members.flush();
            memberCache.evictAfterCommit(conversationId);
            events.publishEvent(new ConversationChanged(conversationId, views.payloads(conversationId, List.of(reopenedFor)), List.of()));
        }
        return new WriteResult(dto, false);
    }

    private Optional<MessageDto> findDuplicate(AuthUser me, Long conversationId, String clientMessageId) {
        return messages.findBySenderIdAndClientMessageId(me.id(), clientMessageId).map(m -> {
            if (!m.getConversationId().equals(conversationId)) {
                throw ApiException.conflict("clientMessageId was already used in another conversation");
            }
            return mapper.message(m);
        });
    }

    /**
     * Pages of history, oldest first, as this user may see it.
     * afterSeq: messages newer than seq (catch-up after reconnect); beforeSeq: older page; neither: latest.
     */
    @Transactional(readOnly = true)
    public List<MessageDto> history(AuthUser me, Long conversationId, Long beforeSeq, Long afterSeq, Integer limit) {
        guard.requireCanRead(conversationId, me.id());
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit == null ? 50 : limit, MAX_PAGE)));
        if (afterSeq != null) {
            return messages.visibleAfter(conversationId, afterSeq, me.id(), page).stream().map(mapper::message).toList();
        }
        List<Message> desc = beforeSeq != null
                ? messages.visibleBefore(conversationId, beforeSeq, me.id(), page)
                : messages.latestVisible(conversationId, me.id(), page);
        List<MessageDto> result = new ArrayList<>(desc.stream().map(mapper::message).toList());
        Collections.reverse(result);
        return result;
    }
}
