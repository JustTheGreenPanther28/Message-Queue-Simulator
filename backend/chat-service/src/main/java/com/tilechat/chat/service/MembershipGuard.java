package com.tilechat.chat.service;

import com.tilechat.chat.domain.ConversationType;
import com.tilechat.chat.domain.MemberStatus;
import com.tilechat.chat.dto.ConversationAccess;
import com.tilechat.common.web.ApiException;
import org.springframework.stereotype.Component;

/**
 * Access rules. Not a member (or the chat is hidden from you) → 404, so ids of other chats
 * don't leak. An unanswered request: you may preview a direct chat but not reply; a group
 * invite shows nothing until you accept.
 */
@Component
public class MembershipGuard {

    private final MemberCache cache;

    public MembershipGuard(MemberCache cache) {
        this.cache = cache;
    }

    public ConversationAccess.Member requireVisible(Long conversationId, Long userId) {
        ConversationAccess access = cache.access(conversationId);
        return access == null ? notFound() : access.member(userId).filter(ConversationAccess.Member::visible)
                .orElseGet(MembershipGuard::notFound);
    }

    public void requireCanRead(Long conversationId, Long userId) {
        ConversationAccess.Member m = requireVisible(conversationId, userId);
        if (m.status() == MemberStatus.PENDING && cache.access(conversationId).type() == ConversationType.GROUP) {
            throw ApiException.forbidden("Accept the invite to see this group");
        }
    }

    public void requireAccepted(Long conversationId, Long userId) {
        if (requireVisible(conversationId, userId).status() == MemberStatus.PENDING) {
            throw ApiException.forbidden("Accept this request first");
        }
    }

    public boolean isAccepted(Long conversationId, Long userId) {
        ConversationAccess access = cache.access(conversationId);
        return access != null && access.member(userId)
                .filter(m -> m.visible() && m.status() == MemberStatus.ACCEPTED).isPresent();
    }

    private static <T> T notFound() {
        throw ApiException.notFound("Conversation not found");
    }
}
