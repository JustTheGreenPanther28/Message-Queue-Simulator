package com.tilechat.common.consumer;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/** Remembers which events a consumer already handled (the "idempotent consumer" pattern). */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent implements Persistable<String> {

    @Id
    @Column(length = 120)
    private String id;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {}

    public ProcessedEvent(String id) {
        this.id = id;
        this.processedAt = Instant.now();
    }

    @Override
    public String getId() { return id; }

    /** Always INSERT (never merge): a duplicate must fail on the primary key. */
    @Override
    public boolean isNew() { return true; }
}
