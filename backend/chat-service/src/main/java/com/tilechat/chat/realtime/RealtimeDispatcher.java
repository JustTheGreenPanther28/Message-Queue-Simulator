package com.tilechat.chat.realtime;

import com.tilechat.chat.dto.ConversationUpdate;
import com.tilechat.chat.dto.ReceiptChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pushes committed changes to connected clients. Push is best-effort: a client that
 * is offline or misses a frame catches up through REST (history after its last seq).
 */
@Component
public class RealtimeDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RealtimeDispatcher.class);

    public static final String MESSAGES = "/queue/messages";
    public static final String RECEIPTS = "/queue/receipts";
    public static final String CONVERSATIONS = "/queue/conversations";
    public static final String USERS = "/queue/users";

    private final SimpMessagingTemplate ws;

    public RealtimeDispatcher(SimpMessagingTemplate ws) {
        this.ws = ws;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void on(RealtimeEvents.MessagePosted e) {
        e.audience().forEach(uid -> send(uid, MESSAGES, e.message()));
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void on(RealtimeEvents.ReceiptsChanged e) {
        Map<Long, List<ReceiptChange>> bySender = e.changes().stream()
                .collect(Collectors.groupingBy(ReceiptChange::senderId));
        bySender.forEach((sender, changes) -> send(sender, RECEIPTS, changes));
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void on(RealtimeEvents.ConversationChanged e) {
        e.payloads().forEach((uid, conversation) -> send(uid, CONVERSATIONS, ConversationUpdate.upsert(conversation)));
        e.removedUserIds().forEach(uid -> send(uid, CONVERSATIONS, ConversationUpdate.removed(e.conversationId())));
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void on(RealtimeEvents.ProfileChanged e) {
        e.audience().forEach(uid -> send(uid, USERS, e.profile()));
    }

    private void send(Long userId, String destination, Object payload) {
        try {
            ws.convertAndSendToUser(String.valueOf(userId), destination, payload);
        } catch (Exception ex) {
            log.warn("WebSocket push to user {} on {} failed: {}", userId, destination, ex.getMessage());
        }
    }
}
