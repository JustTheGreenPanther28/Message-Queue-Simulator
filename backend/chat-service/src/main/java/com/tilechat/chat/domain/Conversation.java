package com.tilechat.chat.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "conversations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_conversations_direct_key", columnNames = "direct_key"),
        @UniqueConstraint(name = "uk_conversations_creation_key", columnNames = {"created_by", "creation_key"})
})
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ConversationType type;

    @Column(length = 64)
    private String name;

    /** "smallerId:largerId" for direct chats; the unique constraint makes the pair's chat unique. */
    @Column(name = "direct_key", length = 41)
    private String directKey;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /** Client's Idempotency-Key for group creation, so a retried request doesn't create two groups. */
    @Column(name = "creation_key", length = 64)
    private String creationKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Last assigned message sequence number. Only changed while holding this row's lock. */
    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    @Column(name = "last_message_id")
    private Long lastMessageId;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    protected Conversation() {}

    private Conversation(ConversationType type, String name, String directKey, Long createdBy, String creationKey) {
        this.type = type;
        this.name = name;
        this.directKey = directKey;
        this.createdBy = createdBy;
        this.creationKey = creationKey;
        this.createdAt = Instant.now();
        this.lastActivityAt = this.createdAt;
    }

    public static Conversation direct(Long a, Long b, Long createdBy) {
        return new Conversation(ConversationType.DIRECT, null, directKey(a, b), createdBy, null);
    }

    public static Conversation group(String name, Long createdBy, String creationKey) {
        return new Conversation(ConversationType.GROUP, name, null, createdBy, creationKey);
    }

    public static String directKey(Long a, Long b) {
        return Math.min(a, b) + ":" + Math.max(a, b);
    }

    public long nextSeq() {
        return ++lastSeq;
    }

    public void recordMessage(Message message) {
        this.lastMessageId = message.getId();
        this.lastActivityAt = message.getCreatedAt();
    }

    public boolean isGroup() { return type == ConversationType.GROUP; }
    public boolean isDirect() { return type == ConversationType.DIRECT; }

    public Long getId() { return id; }
    public ConversationType getType() { return type; }
    public String getName() { return name; }
    public Long getCreatedBy() { return createdBy; }
    public Long getLastMessageId() { return lastMessageId; }
    public long getLastSeq() { return lastSeq; }
    public Instant getLastActivityAt() { return lastActivityAt; }
}
