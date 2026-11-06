package com.tilechat.chat.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** Per-recipient delivery/read state of one message. */
@Entity
@Table(name = "message_receipts",
        uniqueConstraints = @UniqueConstraint(name = "uk_receipt_message_user", columnNames = {"message_id", "user_id"}),
        indexes = @Index(name = "idx_receipt_user_conv", columnList = "user_id, conversation_id"))
public class MessageReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "read_at")
    private Instant readAt;

    /**
     * The recipient has blocked the sender: they never see this message, and since they never
     * acknowledge it, the sender keeps seeing a single tick (silent block).
     */
    @Column(nullable = false)
    private boolean suppressed;

    protected MessageReceipt() {}

    public MessageReceipt(Long messageId, Long conversationId, Long userId, boolean suppressed) {
        this.messageId = messageId;
        this.conversationId = conversationId;
        this.userId = userId;
        this.suppressed = suppressed;
    }
}
