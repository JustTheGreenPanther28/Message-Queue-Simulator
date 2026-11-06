package com.tilechat.chat.service;

import com.tilechat.chat.dto.ConversationAccess;
import com.tilechat.chat.repository.ConversationMemberRepository;
import com.tilechat.chat.repository.ConversationRepository;
import com.tilechat.common.tx.AfterCommit;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Redis cache of each conversation's type and members (with their request status). Checked
 * on every read path. Write paths read members from the DB under the conversation lock
 * instead, so a stale entry can never decide who receives a message.
 */
@Component
public class MemberCache {

    static final String CACHE = "conversation-access-v2"; // bump when the cached shape changes

    private final ConversationRepository conversations;
    private final ConversationMemberRepository members;
    private final CacheManager cacheManager;

    public MemberCache(ConversationRepository conversations, ConversationMemberRepository members, CacheManager cacheManager) {
        this.conversations = conversations;
        this.members = members;
        this.cacheManager = cacheManager;
    }

    /** @return null if the conversation doesn't exist */
    @Cacheable(cacheNames = CACHE, key = "#conversationId", unless = "#result == null")
    public ConversationAccess access(Long conversationId) {
        return conversations.findById(conversationId).map(c -> new ConversationAccess(c.getType(), new ArrayList<>(
                members.findByConversationIdOrderById(conversationId).stream()
                        .map(m -> new ConversationAccess.Member(m.getUserId(), m.getStatus(), m.isHidden(), m.isDeleted()))
                        .toList()))).orElse(null);
    }

    /** Evict after commit: evicting earlier would let a concurrent reader re-cache old data. */
    public void evictAfterCommit(Long conversationId) {
        AfterCommit.run(() -> Objects.requireNonNull(cacheManager.getCache(CACHE)).evict(conversationId));
    }
}
