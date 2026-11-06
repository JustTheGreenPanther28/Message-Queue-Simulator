package com.tilechat.user.service;

import com.tilechat.common.security.JwtService;
import com.tilechat.common.web.ApiException;
import com.tilechat.user.domain.User;
import com.tilechat.user.dto.AuthResponse;
import com.tilechat.user.dto.LoginRequest;
import com.tilechat.user.dto.RegisterRequest;
import com.tilechat.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final UserEventPublisher events;
    private final UserViews views;
    /** Compared against when the user doesn't exist, so response time doesn't reveal valid usernames. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt, UserEventPublisher events, UserViews views) {
        this.views = views;
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.events = events;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String username = normalize(req.username());
        if (users.existsByUsername(username)) {
            throw ApiException.conflict("That username is taken");
        }
        User user;
        try {
            // Two concurrent sign-ups with the same name: the unique constraint decides the winner.
            user = users.saveAndFlush(new User(username, req.displayName().trim(), encoder.encode(req.password())));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("That username is taken");
        }
        events.registered(user);
        return new AuthResponse(jwt.issue(user.getId(), user.getUsername()), views.of(user));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        User user = users.findByUsername(normalize(req.username())).orElse(null);
        String hash = user != null ? user.getPasswordHash() : dummyHash;
        boolean ok = encoder.matches(req.password(), hash);
        if (user == null || !ok) {
            throw ApiException.unauthorized("Invalid username or password");
        }
        return new AuthResponse(jwt.issue(user.getId(), user.getUsername()), views.of(user));
    }

    private static String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
