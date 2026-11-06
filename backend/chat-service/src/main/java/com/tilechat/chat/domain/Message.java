package com.tilechat.chat.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "messages", uniqueConstraints = {
        // Strict, gap-free ordering inside a conversation.
        @UniqueConstraint(name = "uk_messages_conv_seq", columnNames = {"conversation_id", "seq"}),
        // Idempotency: a client retry with the same id can never create a second message.
        @UniqueConstraint(name = "uk_messages_sender_client_id", columnNames = {"sender_id", "client_message_id"})
})
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(nullable = false)
    private long seq;

    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    @Column(name = "client_message_id", nullable = false, length = 64)
    private String clientMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MessageType type;

    @Column(length = 4000)
    private String content;

    @Column(name = "image_key", length = 200)
    private String imageKey;

    /** {@link MessageStatus#level()}; stored as a number so "only move forward" is a simple compare. */
    @Column(name = "status_level", nullable = false)
    private int statusLevel;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Message() {}

    public Message(Long conversationId, long seq, Long senderId, String clientMessageId,
                   MessageType type, String content, String imageKey) {
        this.conversationId = conversationId;
        this.seq = seq;
        this.senderId = senderId;
        this.clientMessageId = clientMessageId;
        this.type = type;
        this.content = content;
        this.imageKey = imageKey;
        this.statusLevel = MessageStatus.SENT.level();
        this.createdAt = Instant.now();
    }

    /** Monotonic: returns true only if the status actually moved forward. */
    public boolean advanceTo(MessageStatus target) {
        if (target.level() <= statusLevel) return false;
        statusLevel = target.level();
        return true;
    }

    public MessageStatus getStatus() { return MessageStatus.ofLevel(statusLevel); }

    public Long getId() { return id; }
    public Long getConversationId() { return conversationId; }
    public long getSeq() { return seq; }
    public Long getSenderId() { return senderId; }
    public String getClientMessageId() { return clientMessageId; }
    public MessageType getType() { return type; }
    public String getContent() { return content; }
    public String getImageKey() { return imageKey; }
    public Instant getCreatedAt() { return createdAt; }
}
