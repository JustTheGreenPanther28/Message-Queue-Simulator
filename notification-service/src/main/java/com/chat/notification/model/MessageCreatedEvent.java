package com.chat.notification.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * The event contract between the two services. Notification Service keeps its
 * own copy of this class — it does not share code or a database with Message
 * Service. Unknown fields are ignored so the producer can add fields safely.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageCreatedEvent(
        Long messageId,
        Long conversationId,
        Long senderId,
        Long seq,
        String preview,
        List<Long> recipientIds,
        Instant createdAt
) {
}
