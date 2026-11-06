package com.tilechat.chat.dto;

import com.tilechat.chat.domain.ConversationType;

import java.time.Instant;
import java.util.List;

/** unreadCount is per viewer; it's null in real-time pushes (the client keeps its own count). */
public record ConversationDto(Long id, ConversationType type, String name, Long createdBy, List<MemberDto> members,
                              MessageDto lastMessage, Long unreadCount, Instant lastActivityAt) {
}
