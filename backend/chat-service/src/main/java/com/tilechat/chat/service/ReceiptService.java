package com.tilechat.chat.service;

import com.tilechat.chat.domain.Message;
import com.tilechat.chat.domain.MessageStatus;
import com.tilechat.chat.dto.ReceiptChange;
import com.tilechat.chat.realtime.RealtimeEvents.ReceiptsChanged;
import com.tilechat.chat.repository.MessageReceiptRepository;
import com.tilechat.chat.repository.MessageRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Delivery and read receipts.
 *
 * <p>Concurrency: each recipient only updates its own receipt rows, with conditional
 * updates ("where delivered_at is null"), so repeated or concurrent acks are harmless.
 * The aggregate status is then recomputed while holding a lock on the message rows
 * (taken in id order to avoid deadlocks). Because every recompute is serialised on that
 * lock and runs after its own receipt update, the last one always sees all committed
 * receipts, so a status can't get stuck (two last readers at once both see "1 unread"
 * without the lock). {@link Message#advanceTo} only moves forward, so it never regresses.
 *
 * <p>Unanswered requests send no receipts, and suppressed receipts (silent block) are never
 * acknowledged, so the sender keeps seeing a single tick.
 */
@Service
public class ReceiptService {

    private static final int MAX_BATCH = 500;

    private final MessageReceiptRepository receipts;
    private final MessageRepository messages;
    private final MembershipGuard guard;
    private final ApplicationEventPublisher events;

    public ReceiptService(MessageReceiptRepository receipts, MessageRepository messages,
                          MembershipGuard guard, ApplicationEventPublisher events) {
        this.receipts = receipts;
        this.messages = messages;
        this.guard = guard;
        this.events = events;
    }

    /** The recipient's device received these messages (or everything pending, if null/empty). */
    @Transactional
    public void markDelivered(Long me, List<Long> messageIds) {
        List<Long> pending = (messageIds == null || messageIds.isEmpty())
                ? receipts.findUndelivered(me, PageRequest.of(0, MAX_BATCH))
                : receipts.findUndeliveredAmong(me, messageIds);
        if (pending.isEmpty()) return;
        receipts.markDelivered(me, pending, Instant.now());
        recompute(pending);
    }

    /** The recipient opened the conversation: everything in it is read. No-op for an unanswered request. */
    @Transactional
    public void markRead(Long me, Long conversationId) {
        guard.requireVisible(conversationId, me);
        if (!guard.isAccepted(conversationId, me)) return;
        List<Long> unread = receipts.findUnreadIn(me, conversationId);
        if (unread.isEmpty()) return;
        receipts.markRead(me, unread, Instant.now());
        recompute(unread);
    }

    /** After accepting a request: everything already on the device counts as delivered. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markDeliveredIn(Long me, Long conversationId) {
        List<Long> pending = receipts.findUndeliveredIn(me, conversationId);
        if (pending.isEmpty()) return;
        receipts.markDelivered(me, pending, Instant.now());
        recompute(pending);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recompute(Collection<Long> messageIds) {
        if (messageIds.isEmpty()) return;
        List<Message> locked = messages.lockByIds(new TreeSet<>(messageIds));

        Map<Long, long[]> pending = new HashMap<>();
        for (Object[] row : receipts.pendingCounts(messageIds)) {
            pending.put((Long) row[0], new long[]{((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
        }

        List<ReceiptChange> changes = new ArrayList<>();
        for (Message m : locked) {
            long[] p = pending.get(m.getId());
            // No receipt rows left (every recipient left the group) counts as fully read.
            MessageStatus target = (p == null || p[1] == 0) ? MessageStatus.READ
                    : p[0] == 0 ? MessageStatus.DELIVERED : MessageStatus.SENT;
            if (m.advanceTo(target)) {
                changes.add(new ReceiptChange(m.getSenderId(), m.getConversationId(), m.getId(), target));
            }
        }
        if (!changes.isEmpty()) events.publishEvent(new ReceiptsChanged(changes));
    }
}
