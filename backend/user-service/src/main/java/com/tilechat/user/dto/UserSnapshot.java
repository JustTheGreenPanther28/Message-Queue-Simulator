package com.tilechat.user.dto;

import com.tilechat.user.domain.User;

/** What we cache in Redis: the stored state only. Presigned URLs expire, so they're added on the way out. */
public record UserSnapshot(Long id, String username, String displayName, String avatarKey, long version) {
    public static UserSnapshot from(User u) {
        return new UserSnapshot(u.getId(), u.getUsername(), u.getDisplayName(), u.getAvatarKey(), u.getVersion());
    }
}
