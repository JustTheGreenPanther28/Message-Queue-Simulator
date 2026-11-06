package com.tilechat.common.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class JwtServiceTest {

    private final JwtService jwt = new JwtService("test-secret-that-is-at-least-32-bytes!!", Duration.ofHours(1));

    @Test
    void roundTrip() {
        AuthUser user = jwt.verify(jwt.issue(42L, "alice"));
        assertThat(user.id()).isEqualTo(42L);
        assertThat(user.username()).isEqualTo("alice");
        assertThat(user.getName()).isEqualTo("42"); // used for STOMP /user routing
    }

    @Test
    void rejectsTamperedOrForeignTokens() {
        String token = jwt.issue(1L, "bob");
        String tampered = token.substring(0, token.length() - 3) + "abc";
        JwtService other = new JwtService("another-secret-that-is-32-bytes-long!!", Duration.ofHours(1));

        assertThat(jwt.verifyHeader("Bearer " + tampered)).isNull();
        assertThat(jwt.verifyHeader("Bearer " + other.issue(1L, "bob"))).isNull();
        assertThat(jwt.verifyHeader("garbage")).isNull();
        assertThat(jwt.verifyHeader(null)).isNull();
    }

    @Test
    void rejectsExpiredTokens() {
        JwtService shortLived = new JwtService("test-secret-that-is-at-least-32-bytes!!", Duration.ofMinutes(-5)); // beyond the 30 s clock-skew allowance
        assertThat(jwt.verifyHeader(shortLived.issue(1L, "x"))).isNull();
    }

    @Test
    void rejectsTokensFromAnotherIssuer() {
        var key = io.jsonwebtoken.security.Keys.hmacShaKeyFor("test-secret-that-is-at-least-32-bytes!!".getBytes());
        String foreign = io.jsonwebtoken.Jwts.builder().issuer("someone-else").subject("1").signWith(key).compact();
        assertThat(jwt.verifyHeader(foreign)).isNull();
    }

    @Test
    void refusesWeakSecret() {
        assertThatThrownBy(() -> new JwtService("short", Duration.ofHours(1))).isInstanceOf(IllegalStateException.class);
    }
}
