package com.tilechat.chat.dto;

/** Pushed on /user/queue/conversations. action: UPSERT (created/changed) or REMOVED (you left). */
public record ConversationUpdate(String action, Long conversationId, ConversationDto conversation) {
    public static ConversationUpdate upsert(ConversationDto c) { return new ConversationUpdate("UPSERT", c.id(), c); }
    public static ConversationUpdate removed(Long id) { return new ConversationUpdate("REMOVED", id, null); }
}
