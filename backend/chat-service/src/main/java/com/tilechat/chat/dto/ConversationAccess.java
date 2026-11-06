package com.tilechat.chat.dto;

import com.tilechat.chat.domain.ConversationType;
import com.tilechat.chat.domain.MemberStatus;

import java.util.List;
import java.util.Optional;

/** Cached (Redis) answer to "who may do what in this conversation". */
public record ConversationAccess(ConversationType type, List<Member> members) {

    public record Member(Long userId, MemberStatus status, boolean hidden, boolean deleted) {
        public boolean visible() { return !hidden && !deleted && status != MemberStatus.REJECTED; }
    }

    public Optional<Member> member(Long userId) {
        return members.stream().filter(m -> m.userId().equals(userId)).findFirst();
    }
}
