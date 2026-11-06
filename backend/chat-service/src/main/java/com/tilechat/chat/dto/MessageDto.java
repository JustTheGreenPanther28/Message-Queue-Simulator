package com.tilechat.chat.dto;

import com.tilechat.chat.domain.MessageStatus;
import com.tilechat.chat.domain.MessageType;

import java.time.Instant;

/** imageUrl is a short-lived presigned URL, signed only for people allowed to see the message. */
public record MessageDto(Long id, Long conversationId, long seq, Long senderId, String clientMessageId,
                         MessageType type, String content, String imageUrl, MessageStatus status, Instant createdAt) {
}
