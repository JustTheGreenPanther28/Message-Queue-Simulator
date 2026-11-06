package com.chat.notification.model;

import java.time.Instant;

public record Notification(
        Long id,
        Long messageId,
        Long conversationId,
        Long senderId,
        Long recipientId,
        String preview,
        String status,
        Instant createdAt
) {
}
