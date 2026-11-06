package com.tilechat.gateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitKeysTest {

    static final String SECRET = "test-secret-that-is-at-least-32-bytes!!";
    final RateLimitKeys keys = new RateLimitKeys(SECRET);

    private static String token(String secret, String issuer, String sub) {
        return Jwts.builder().issuer(issuer).subject(sub)
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private String key(String authorization) {
        var req = MockServerHttpRequest.get("/api/conversations").header("X-Forwarded-For", "203.0.113.7");
        if (authorization != null) req.header("Authorization", authorization);
        return keys.apiKeyResolver().resolve(MockServerWebExchange.from(req)).block();
    }

    @Test
    void signedInTrafficCountsPerUser() {
        assertThat(key("Bearer " + token(SECRET, "tilechat", "42"))).isEqualTo("api:user:42");
    }

    @Test
    void forgedOrForeignTokensFallBackToTheIp() {
        assertThat(key("Bearer " + token("another-secret-that-is-32-bytes-long!!", "tilechat", "42"))).isEqualTo("api:ip:203.0.113.7");
        assertThat(key("Bearer " + token(SECRET, "someone-else", "42"))).isEqualTo("api:ip:203.0.113.7");
        assertThat(key(null)).isEqualTo("api:ip:203.0.113.7");
    }

    @Test
    void loginIsAlwaysLimitedByIpInItsOwnBucket() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login")
                .header("X-Forwarded-For", "203.0.113.7, 10.0.0.1")
                .header("Authorization", "Bearer " + token(SECRET, "tilechat", "42")));
        assertThat(keys.authKeyResolver().resolve(exchange).block()).isEqualTo("auth:ip:203.0.113.7");
    }
}
