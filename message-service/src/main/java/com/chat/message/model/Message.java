package com.chat.message.model;

import java.time.Instant;

public record Message(
        Long id,
        Long conversationId,
        Long senderId,
        Long seq,
        String content,
        Long version,
        Instant createdAt,
        Instant updatedAt
) {
}
