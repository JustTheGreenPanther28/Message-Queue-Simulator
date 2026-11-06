package com.chat.notification.repository;

import com.chat.notification.model.Notification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class NotificationRepository {

    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if a new row was inserted, false if it already existed (duplicate delivery). */
    public boolean insertIfAbsent(long messageId, long conversationId, long senderId,
                                  long recipientId, String preview) {
        return jdbc.update(
                "INSERT INTO notifications (message_id, conversation_id, sender_id, recipient_id, preview) " +
                "VALUES (?, ?, ?, ?, ?) ON CONFLICT (message_id, recipient_id) DO NOTHING",
                messageId, conversationId, senderId, recipientId, preview) > 0;
    }

    public List<Notification> findForRecipient(long recipientId, int limit) {
        return jdbc.query(
                "SELECT id, message_id, conversation_id, sender_id, recipient_id, preview, status, created_at " +
                "FROM notifications WHERE recipient_id = ? ORDER BY created_at DESC LIMIT ?",
                (rs, i) -> new Notification(
                        rs.getLong("id"),
                        rs.getLong("message_id"),
                        rs.getLong("conversation_id"),
                        rs.getLong("sender_id"),
                        rs.getLong("recipient_id"),
                        rs.getString("preview"),
                        rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                recipientId, limit);
    }
}
