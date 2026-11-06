package com.tilechat.common.events;

import java.time.Instant;
import java.util.List;

public record MembersAddedEvent(
        String eventId,
        Long conversationId,
        String conversationName,
        Long actorId,
        String actorName,
        String actorAvatarKey,
        List<Long> addedUserIds,
        Instant occurredAt) {
}
