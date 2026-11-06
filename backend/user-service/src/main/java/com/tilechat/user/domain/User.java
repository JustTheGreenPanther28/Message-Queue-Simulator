package com.tilechat.user.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "uk_users_username", columnNames = "username"))
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Always stored lower-case, so uniqueness is case-insensitive. */
    @Column(nullable = false, length = 32)
    private String username;

    @Column(name = "display_name", nullable = false, length = 40)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "avatar_key", length = 200)
    private String avatarKey;

    /** Optimistic lock: concurrent profile edits cannot silently overwrite each other. */
    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {}

    public User(String username, String displayName, String passwordHash) {
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void rename(String displayName) {
        this.displayName = displayName;
        this.updatedAt = Instant.now();
    }

    /** @return the previous avatar key, so the caller can delete the old object */
    public String changeAvatar(String avatarKey) {
        String old = this.avatarKey;
        this.avatarKey = avatarKey;
        this.updatedAt = Instant.now();
        return old;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
    public String getAvatarKey() { return avatarKey; }
    public long getVersion() { return version; }
}
