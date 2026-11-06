package com.tilechat.user.service;

import com.tilechat.common.storage.ObjectStorage;
import com.tilechat.user.domain.User;
import com.tilechat.user.dto.UserDto;
import com.tilechat.user.dto.UserSnapshot;
import org.springframework.stereotype.Component;

/** Turns stored state into API responses, signing image URLs at the last moment. */
@Component
public class UserViews {

    private final ObjectStorage storage;

    public UserViews(ObjectStorage storage) {
        this.storage = storage;
    }

    public UserDto of(UserSnapshot s) {
        return new UserDto(s.id(), s.username(), s.displayName(), storage.urlFor(s.avatarKey()), s.version());
    }

    public UserDto of(User u) {
        return of(UserSnapshot.from(u));
    }
}
