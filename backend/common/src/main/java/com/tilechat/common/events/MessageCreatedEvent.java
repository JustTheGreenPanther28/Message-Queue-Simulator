package com.tilechat.common.events;

import java.time.Instant;
import java.util.List;

public record MessageCreatedEvent(
        String eventId,
        Long messageId,
        Long conversationId,
        String conversationType,
        String conversationName,
        Long senderId,
        String senderName,
        String senderAvatarKey,
        String preview,
        List<Long> recipientIds,
        /** Recipients for whom this chat is still an unanswered message request. */
        List<Long> requestRecipientIds,
        Instant occurredAt) {
}
