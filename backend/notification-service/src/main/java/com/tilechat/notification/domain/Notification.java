package com.tilechat.notification.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "notifications",
        // A redelivered event can never create a second notification for the same user.
        uniqueConstraints = @UniqueConstraint(name = "uk_notification_user_source", columnNames = {"user_id", "source_key"}),
        indexes = @Index(name = "idx_notification_user", columnList = "user_id, id"))
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationType type;

    @Column(name = "source_key", nullable = false, length = 80)
    private String sourceKey;

    @Column(name = "conversation_id")
    private Long conversationId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 300)
    private String body;

    @Column(name = "image_key", length = 200)
    private String imageKey;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Notification() {}

    public Notification(Long userId, NotificationType type, String sourceKey, Long conversationId,
                        String title, String body, String imageKey) {
        this.userId = userId;
        this.type = type;
        this.sourceKey = sourceKey;
        this.conversationId = conversationId;
        this.title = cut(title, 100);
        this.body = cut(body, 300);
        this.imageKey = imageKey;
        this.createdAt = Instant.now();
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public NotificationType getType() { return type; }
    public String getSourceKey() { return sourceKey; }
    public Long getConversationId() { return conversationId; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getImageKey() { return imageKey; }
    public boolean isRead() { return read; }
    public Instant getCreatedAt() { return createdAt; }
}
