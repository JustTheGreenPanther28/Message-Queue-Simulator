package com.tilechat.chat.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Local read-only copy of a user-service profile, kept up to date from user events
 * (event-carried state transfer). chat-service never calls user-service synchronously.
 */
@Entity
@Table(name = "user_profiles")
public class UserProfile {

    @Id
    private Long id;

    @Column(nullable = false, length = 32)
    private String username;

    @Column(name = "display_name", nullable = false, length = 40)
    private String displayName;

    @Column(name = "avatar_key", length = 200)
    private String avatarKey;

    /** user-service's version of this profile; older events are ignored. */
    @Column(name = "source_version", nullable = false)
    private long sourceVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserProfile() {}

    public UserProfile(Long id) {
        this.id = id;
        this.sourceVersion = -1;
    }

    /** Applies a snapshot if it's newer than what we have. Returns true if anything changed. */
    public boolean apply(String username, String displayName, String avatarKey, long version) {
        if (version <= sourceVersion) return false;
        this.username = username;
        this.displayName = displayName;
        this.avatarKey = avatarKey;
        this.sourceVersion = version;
        this.updatedAt = Instant.now();
        return true;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getAvatarKey() { return avatarKey; }
}
