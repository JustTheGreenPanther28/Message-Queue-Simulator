package com.chat.message.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ConversationRepository {

    private final JdbcTemplate jdbc;

    public ConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long create(String name) {
        return jdbc.queryForObject(
                "INSERT INTO conversations (name) VALUES (?) RETURNING id", Long.class, name);
    }

    public void addMembers(long conversationId, List<Long> userIds) {
        jdbc.batchUpdate(
                "INSERT INTO conversation_members (conversation_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                userIds, userIds.size(),
                (ps, userId) -> {
                    ps.setLong(1, conversationId);
                    ps.setLong(2, userId);
                });
    }

    public Optional<String> findName(long conversationId) {
        return jdbc.query("SELECT name FROM conversations WHERE id = ?",
                        (rs, i) -> rs.getString("name"), conversationId)
                .stream().findFirst();
    }

    public List<Long> findMemberIds(long conversationId) {
        return jdbc.query(
                "SELECT user_id FROM conversation_members WHERE conversation_id = ? ORDER BY user_id",
                (rs, i) -> rs.getLong("user_id"), conversationId);
    }

    public boolean isMember(long conversationId, long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation_members WHERE conversation_id = ? AND user_id = ?",
                Integer.class, conversationId, userId);
        return count != null && count > 0;
    }

    /**
     * Atomically reserves the next sequence number for a conversation.
     *
     * The UPDATE takes a row-level lock on the conversation row. Any other
     * transaction sending to the same conversation blocks here until this one
     * commits or rolls back, so sequence numbers are strictly ordered with no
     * duplicates. Different conversations lock different rows, so they never
     * block each other.
     *
     * Returns empty if the conversation does not exist.
     */
    public Optional<Long> reserveNextSeq(long conversationId) {
        return jdbc.query(
                        "UPDATE conversations SET last_seq = last_seq + 1 WHERE id = ? RETURNING last_seq",
                        (rs, i) -> rs.getLong(1), conversationId)
                .stream().findFirst();
    }
}
