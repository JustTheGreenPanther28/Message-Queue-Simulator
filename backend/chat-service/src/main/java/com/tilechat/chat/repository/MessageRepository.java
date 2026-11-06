package com.tilechat.chat.repository;

import com.tilechat.chat.domain.Message;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    /**
     * Hides what the viewer must not see: messages from someone they had blocked (suppressed
     * receipt) and anything before they deleted the chat (cleared_seq).
     */
    String VISIBLE_TO_VIEWER = """
             and not exists (select r.id from MessageReceipt r
                              where r.messageId = m.id and r.userId = :viewer and r.suppressed = true)
             and m.seq > coalesce((select cm.clearedSeq from ConversationMember cm
                                    where cm.conversationId = m.conversationId and cm.userId = :viewer), 0)
            """;

    @Query("select m from Message m where m.conversationId = :cid" + VISIBLE_TO_VIEWER + " order by m.seq desc")
    List<Message> latestVisible(@Param("cid") Long conversationId, @Param("viewer") Long viewerId, Pageable page);

    @Query("select m from Message m where m.conversationId = :cid and m.seq < :seq" + VISIBLE_TO_VIEWER + " order by m.seq desc")
    List<Message> visibleBefore(@Param("cid") Long conversationId, @Param("seq") long seq, @Param("viewer") Long viewerId, Pageable page);

    /** Used by clients to catch up after a reconnect. */
    @Query("select m from Message m where m.conversationId = :cid and m.seq > :seq" + VISIBLE_TO_VIEWER + " order by m.seq asc")
    List<Message> visibleAfter(@Param("cid") Long conversationId, @Param("seq") long seq, @Param("viewer") Long viewerId, Pageable page);

    Optional<Message> findBySenderIdAndClientMessageId(Long senderId, String clientMessageId);

    /** Locks in id order, so two transactions locking overlapping sets can't deadlock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Message m where m.id in :ids order by m.id")
    List<Message> lockByIds(@Param("ids") Collection<Long> ids);
}
