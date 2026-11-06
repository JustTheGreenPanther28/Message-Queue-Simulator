package com.tilechat.chat.service;

import com.tilechat.chat.domain.Conversation;
import com.tilechat.chat.domain.ConversationMember;
import com.tilechat.chat.domain.MemberStatus;
import com.tilechat.chat.domain.UserBlock;
import com.tilechat.chat.dto.MemberDto;
import com.tilechat.chat.realtime.RealtimeEvents.ConversationChanged;
import com.tilechat.chat.repository.ConversationMemberRepository;
import com.tilechat.chat.repository.ConversationRepository;
import com.tilechat.chat.repository.UserBlockRepository;
import com.tilechat.common.security.AuthUser;
import com.tilechat.common.web.ApiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Silent blocking. The blocked person isn't told and nothing changes on their side:
 * <ul>
 *   <li>their direct messages to you are stored but never shown to you (they see one tick);</li>
 *   <li>their new chat requests and group invites to you are created hidden;</li>
 *   <li>requests/invites from them that you haven't answered disappear for you;</li>
 *   <li>you can't message them until you unblock. Group chats you share are unaffected.</li>
 * </ul>
 * Messages sent while blocked stay hidden after unblocking.
 */
@Service
public class BlockService {

    private final TransactionTemplate tx;
    private final UserBlockRepository blocks;
    private final ConversationMemberRepository members;
    private final ConversationRepository conversations;
    private final UserDirectory directory;
    private final ConversationViews views;
    private final MemberCache memberCache;
    private final ApplicationEventPublisher events;

    public BlockService(TransactionTemplate tx, UserBlockRepository blocks, ConversationMemberRepository members,
                        ConversationRepository conversations, UserDirectory directory, ConversationViews views,
                        MemberCache memberCache, ApplicationEventPublisher events) {
        this.tx = tx;
        this.blocks = blocks;
        this.members = members;
        this.conversations = conversations;
        this.directory = directory;
        this.views = views;
        this.memberCache = memberCache;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<MemberDto> blocked(Long me) {
        List<Long> ids = blocks.findByBlockerIdOrderByCreatedAtDesc(me).stream().map(UserBlock::getBlockedId).toList();
        return List.copyOf(directory.members(ids).values());
    }

    /** Idempotent. */
    public void block(AuthUser me, Long target) {
        if (target.equals(me.id())) throw ApiException.badRequest("You can't block yourself");
        directory.requireExisting(List.of(target));
        try {
            tx.executeWithoutResult(s -> {
                if (!blocks.existsByBlockerIdAndBlockedId(me.id(), target)) {
                    blocks.saveAndFlush(new UserBlock(me.id(), target));
                }
                for (ConversationMember pending : members.findByUserIdAndInvitedByAndStatus(me.id(), target, MemberStatus.PENDING)) {
                    if (pending.isHidden()) continue;
                    pending.hide();
                    memberCache.evictAfterCommit(pending.getConversationId());
                    events.publishEvent(new ConversationChanged(pending.getConversationId(), java.util.Map.of(), List.of(me.id())));
                }
            });
        } catch (DataIntegrityViolationException alreadyBlocked) {
            // a concurrent identical request won; the result is the same
        }
    }

    /** Idempotent. A hidden request from them in your direct chat becomes visible again. */
    public void unblock(AuthUser me, Long target) {
        tx.executeWithoutResult(s -> {
            blocks.unblock(me.id(), target);
            conversations.findByDirectKey(Conversation.directKey(me.id(), target))
                    .flatMap(c -> members.findByConversationIdAndUserId(c.getId(), me.id()))
                    .filter(ConversationMember::isHidden)
                    .ifPresent(mine -> {
                        mine.unhide();
                        members.flush();
                        memberCache.evictAfterCommit(mine.getConversationId());
                        events.publishEvent(new ConversationChanged(mine.getConversationId(),
                                views.payloads(mine.getConversationId(), List.of(me.id())), List.of()));
                    });
        });
    }
}
