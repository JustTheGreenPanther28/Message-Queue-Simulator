package com.tilechat.common.consumer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    @Modifying
    @Query("delete from ProcessedEvent p where p.processedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
