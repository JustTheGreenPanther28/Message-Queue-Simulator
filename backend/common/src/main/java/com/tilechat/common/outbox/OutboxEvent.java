package com.tilechat.common.outbox;

import jakarta.persistence.*;

import java.time.Duration;
import java.time.Instant;

/**
 * An event waiting to be published. Written in the same transaction as the business
 * change, so either both happen or neither does.
 */
@Entity
@Table(name = "outbox_events", indexes = @Index(name = "idx_outbox_pending", columnList = "published_at, next_attempt_at"))
public class OutboxEvent {

    private static final Duration MAX_BACKOFF = Duration.ofMinutes(1);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 64)
    private String eventId;

    @Column(nullable = false, length = 100)
    private String exchange;

    @Column(name = "routing_key", nullable = false, length = 100)
    private String routingKey;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    protected OutboxEvent() {}

    public OutboxEvent(String eventId, String exchange, String routingKey, String payload) {
        this.eventId = eventId;
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.payload = payload;
        this.createdAt = Instant.now();
        this.nextAttemptAt = this.createdAt;
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    /** Exponential backoff: 1s, 2s, 4s ... capped at one minute. */
    public void markFailed(String error) {
        attempts++;
        long seconds = Math.min(1L << Math.min(attempts - 1, 10), MAX_BACKOFF.toSeconds());
        nextAttemptAt = Instant.now().plusSeconds(seconds);
        lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
    }

    public String getEventId() { return eventId; }
    public String getExchange() { return exchange; }
    public String getRoutingKey() { return routingKey; }
    public String getPayload() { return payload; }
    public int getAttempts() { return attempts; }
}
