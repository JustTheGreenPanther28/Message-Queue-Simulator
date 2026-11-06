package com.tilechat.chat.repository;

import com.tilechat.chat.domain.ConversationMember;
import com.tilechat.chat.domain.MemberStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ConversationMemberRepository extends JpaRepository<ConversationMember, Long> {

    List<ConversationMember> findByConversationIdOrderById(Long conversationId);

    Optional<ConversationMember> findByConversationIdAndUserId(Long conversationId, Long userId);

    List<ConversationMember> findByConversationIdIn(Collection<Long> conversationIds);

    /** Pending requests/invites this user received from a given person. */
    List<ConversationMember> findByUserIdAndInvitedByAndStatus(Long userId, Long invitedBy, MemberStatus status);

    @Modifying
    @Query("delete from ConversationMember m where m.conversationId = :cid and m.userId = :uid")
    int deleteMember(@Param("cid") Long conversationId, @Param("uid") Long userId);

    /** Everyone who shares at least one conversation with the user. */
    @Query("""
            select distinct m2.userId from ConversationMember m1, ConversationMember m2
             where m1.userId = :uid and m2.conversationId = m1.conversationId and m2.userId <> :uid
               and m2.hidden = false and m2.status <> com.tilechat.chat.domain.MemberStatus.REJECTED
            """)
    List<Long> findContactIds(@Param("uid") Long userId);
}
