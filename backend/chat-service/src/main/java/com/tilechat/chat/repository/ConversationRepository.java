package com.tilechat.chat.repository;

import com.tilechat.chat.domain.Conversation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /**
     * SELECT ... FOR UPDATE on the conversation row. Every write to a conversation (new
     * message, membership change) takes this lock first, which serialises them per
     * conversation while different conversations proceed in parallel.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Conversation c where c.id = :id")
    Optional<Conversation> lockById(@Param("id") Long id);

    Optional<Conversation> findByDirectKey(String directKey);

    Optional<Conversation> findByCreatedByAndCreationKey(Long createdBy, String creationKey);

    @Query("""
            select c from Conversation c
             where c.id in (select m.conversationId from ConversationMember m
                             where m.userId = :userId and m.hidden = false and m.deleted = false
                               and m.status <> com.tilechat.chat.domain.MemberStatus.REJECTED)
             order by c.lastActivityAt desc
            """)
    /** Conversations the user can see (chats and incoming requests). */
    List<Conversation> findAllForUser(@Param("userId") Long userId);
}
