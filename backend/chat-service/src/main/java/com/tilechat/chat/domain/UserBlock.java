package com.tilechat.chat.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "user_blocks", uniqueConstraints = @UniqueConstraint(name = "uk_block_pair", columnNames = {"blocker_id", "blocked_id"}))
public class UserBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "blocker_id", nullable = false)
    private Long blockerId;

    @Column(name = "blocked_id", nullable = false)
    private Long blockedId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserBlock() {}

    public UserBlock(Long blockerId, Long blockedId) {
        this.blockerId = blockerId;
        this.blockedId = blockedId;
        this.createdAt = Instant.now();
    }

    public Long getBlockedId() { return blockedId; }
}
