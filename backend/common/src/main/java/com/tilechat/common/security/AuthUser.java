package com.tilechat.common.security;

import java.security.Principal;

/**
 * The authenticated caller. Implements {@link Principal} so the same object works for
 * HTTP requests and for STOMP sessions (where {@code getName()} is used to route
 * {@code /user/...} destinations, so it must be the user id).
 */
public record AuthUser(Long id, String username) implements Principal {
    @Override
    public String getName() {
        return String.valueOf(id);
    }
}
