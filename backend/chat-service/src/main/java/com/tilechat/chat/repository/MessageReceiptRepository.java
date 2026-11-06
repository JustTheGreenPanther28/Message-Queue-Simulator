package com.tilechat.chat.repository;

import com.tilechat.chat.domain.MessageReceipt;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Acks only count for conversations the user has ACCEPTED (an unanswered request sends no
 * receipts) and never for suppressed receipts (silent block).
 */
public interface MessageReceiptRepository extends JpaRepository<MessageReceipt, Long> {

    String ACKABLE = """
             r.suppressed = false
             and exists (select cm.id from ConversationMember cm
                          where cm.conversationId = r.conversationId and cm.userId = r.userId
                            and cm.status = com.tilechat.chat.domain.MemberStatus.ACCEPTED)
            """;

    @Query("select r.messageId from MessageReceipt r where r.userId = :uid and r.deliveredAt is null and " + ACKABLE + " order by r.messageId")
    List<Long> findUndelivered(@Param("uid") Long userId, Pageable page);

    @Query("select r.messageId from MessageReceipt r where r.userId = :uid and r.deliveredAt is null and r.messageId in :ids and " + ACKABLE)
    List<Long> findUndeliveredAmong(@Param("uid") Long userId, @Param("ids") Collection<Long> messageIds);

    @Query("select r.messageId from MessageReceipt r where r.userId = :uid and r.conversationId = :cid and r.deliveredAt is null and " + ACKABLE)
    List<Long> findUndeliveredIn(@Param("uid") Long userId, @Param("cid") Long conversationId);

    @Query("select r.messageId from MessageReceipt r where r.userId = :uid and r.conversationId = :cid and r.readAt is null and " + ACKABLE)
    List<Long> findUnreadIn(@Param("uid") Long userId, @Param("cid") Long conversationId);

    /** Conditional update ("... is null"): repeating an ack is harmless and can't move a timestamp. */
    @Modifying
    @Query("update MessageReceipt r set r.deliveredAt = :now where r.userId = :uid and r.messageId in :ids and r.deliveredAt is null and r.suppressed = false")
    int markDelivered(@Param("uid") Long userId, @Param("ids") Collection<Long> messageIds, @Param("now") Instant now);

    /** Read implies delivered. */
    @Modifying
    @Query("""
            update MessageReceipt r set r.readAt = :now, r.deliveredAt = coalesce(r.deliveredAt, :now)
             where r.userId = :uid and r.messageId in :ids and r.readAt is null and r.suppressed = false
            """)
    int markRead(@Param("uid") Long userId, @Param("ids") Collection<Long> messageIds, @Param("now") Instant now);

    @Query("select r.messageId from MessageReceipt r where r.userId = :uid and r.conversationId = :cid and r.readAt is null")
    List<Long> findAllUnreadIn(@Param("uid") Long userId, @Param("cid") Long conversationId);

    @Modifying
    @Query("delete from MessageReceipt r where r.userId = :uid and r.conversationId = :cid and r.readAt is null")
    int deleteUnread(@Param("uid") Long userId, @Param("cid") Long conversationId);

    /** Per message: [messageId, recipients not yet delivered, recipients not yet read]. */
    @Query("""
            select r.messageId,
                   sum(case when r.deliveredAt is null then 1 else 0 end),
                   sum(case when r.readAt is null then 1 else 0 end)
              from MessageReceipt r
             where r.messageId in :ids
             group by r.messageId
            """)
    List<Object[]> pendingCounts(@Param("ids") Collection<Long> messageIds);

    /** Deleting a chat: unread messages are dropped for you, never marked read (the sender isn't told). */
    @Modifying
    @Query("update MessageReceipt r set r.suppressed = true where r.userId = :uid and r.conversationId = :cid and r.readAt is null")
    int dismissUnread(@Param("uid") Long userId, @Param("cid") Long conversationId);

    @Query("select r.conversationId, count(r) from MessageReceipt r where r.userId = :uid and r.readAt is null and r.suppressed = false group by r.conversationId")
    List<Object[]> unreadCounts(@Param("uid") Long userId);

    @Query("select r.messageId from MessageReceipt r where r.userId = :uid and r.suppressed = true and r.messageId in :ids")
    List<Long> findSuppressedAmong(@Param("uid") Long userId, @Param("ids") Collection<Long> messageIds);
}
