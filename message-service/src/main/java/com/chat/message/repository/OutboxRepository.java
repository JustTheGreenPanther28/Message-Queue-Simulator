package com.chat.message.repository;

import com.chat.message.model.OutboxEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String eventType, String payload) {
        jdbc.update("INSERT INTO outbox_events (event_type, payload) VALUES (?, ?)", eventType, payload);
    }

    /**
     * FOR UPDATE SKIP LOCKED: if several instances of message-service run the
     * poller at once, each one locks and takes a different set of rows instead
     * of publishing the same events twice or waiting on each other.
     * Must be called inside a transaction.
     */
    public List<OutboxEvent> lockUnpublishedBatch(int limit) {
        return jdbc.query(
                "SELECT id, event_type, payload FROM outbox_events " +
                "WHERE published_at IS NULL ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, i) -> new OutboxEvent(rs.getLong("id"), rs.getString("event_type"), rs.getString("payload")),
                limit);
    }

    public void markPublished(long id) {
        jdbc.update("UPDATE outbox_events SET published_at = now() WHERE id = ?", id);
    }
}
