package com.tilechat.notification.dto;

import com.tilechat.notification.domain.NotificationType;

import java.time.Instant;

/** imageUrl is a short-lived presigned URL. */
public record NotificationDto(Long id, NotificationType type, Long conversationId, String title, String body,
                              String imageUrl, boolean read, Instant createdAt) {
}
