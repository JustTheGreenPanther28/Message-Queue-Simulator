package com.tilechat.common.events;

import java.time.Instant;

/**
 * Published by user-service whenever a profile is created or changed. Images are referenced by
 * object-storage key, never by URL: each service signs its own short-lived URLs. Carries the full
 * state plus a monotonically increasing {@code version}, so consumers can apply events
 * in any order and ignore stale or duplicate ones.
 */
public record UserProfileEvent(
        String eventId,
        Long userId,
        String username,
        String displayName,
        String avatarKey,
        long version,
        Instant occurredAt) {
}
