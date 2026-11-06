package com.tilechat.common.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Locks a batch of due events. SKIP LOCKED lets several instances of the same
     * service relay in parallel without ever picking the same row.
     */
    @Query(value = """
            SELECT * FROM outbox_events
             WHERE published_at IS NULL AND next_attempt_at <= now()
             ORDER BY id
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockNextBatch(@Param("limit") int limit);

    @Modifying
    @Query("delete from OutboxEvent e where e.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
