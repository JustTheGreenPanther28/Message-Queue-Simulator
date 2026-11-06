package com.chat.message.repository;

import com.chat.message.model.Message;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class MessageRepository {

    private static final String COLUMNS =
            "id, conversation_id, sender_id, seq, content, version, created_at, updated_at";

    private static final RowMapper<Message> MAPPER = (rs, i) -> new Message(
            rs.getLong("id"),
            rs.getLong("conversation_id"),
            rs.getLong("sender_id"),
            rs.getLong("seq"),
            rs.getString("content"),
            rs.getLong("version"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant()
    );

    private final JdbcTemplate jdbc;

    public MessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the message. If another request with the same idempotency key
     * already inserted a row, ON CONFLICT DO NOTHING makes this return empty
     * instead of throwing a unique-constraint error.
     */
    public Optional<Message> insert(long conversationId, long senderId, long seq,
                                    String content, String idempotencyKey) {
        return jdbc.query(
                "INSERT INTO messages (conversation_id, sender_id, seq, content, idempotency_key) " +
                "VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (conversation_id, sender_id, idempotency_key) DO NOTHING " +
                "RETURNING " + COLUMNS,
                MAPPER, conversationId, senderId, seq, content, idempotencyKey
        ).stream().findFirst();
    }

    public Optional<Message> findByIdempotencyKey(long conversationId, long senderId, String key) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM messages " +
                "WHERE conversation_id = ? AND sender_id = ? AND idempotency_key = ?",
                MAPPER, conversationId, senderId, key
        ).stream().findFirst();
    }

    public Optional<Message> findById(long id) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM messages WHERE id = ? AND deleted = FALSE",
                MAPPER, id
        ).stream().findFirst();
    }

    /** Latest messages, newest first. */
    public List<Message> findLatest(long conversationId, int limit) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM messages " +
                "WHERE conversation_id = ? AND deleted = FALSE ORDER BY seq DESC LIMIT ?",
                MAPPER, conversationId, limit);
    }

    /** Older page for scrolling up, newest first. */
    public List<Message> findBefore(long conversationId, long beforeSeq, int limit) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM messages " +
                "WHERE conversation_id = ? AND deleted = FALSE AND seq < ? ORDER BY seq DESC LIMIT ?",
                MAPPER, conversationId, beforeSeq, limit);
    }

    /**
     * Optimistic locking: only updates if the row still has the version the
     * client saw. Returns empty when someone else changed it first.
     */
    public Optional<Message> updateContent(long id, String content, long expectedVersion) {
        return jdbc.query(
                "UPDATE messages SET content = ?, version = version + 1, updated_at = now() " +
                "WHERE id = ? AND deleted = FALSE AND version = ? RETURNING " + COLUMNS,
                MAPPER, content, id, expectedVersion
        ).stream().findFirst();
    }

    public boolean softDelete(long id) {
        return jdbc.update(
                "UPDATE messages SET deleted = TRUE, version = version + 1, updated_at = now() " +
                "WHERE id = ? AND deleted = FALSE", id) > 0;
    }
}
