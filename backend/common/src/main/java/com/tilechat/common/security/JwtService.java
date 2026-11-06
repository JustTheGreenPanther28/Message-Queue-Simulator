package com.tilechat.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/** Issues and verifies HMAC-signed JWTs. Every service shares the same secret. */
public class JwtService {

    /** Tokens from anything else (another app sharing the secret by mistake) are rejected. */
    public static final String ISSUER = "tilechat";

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(String secret, Duration ttl) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttl = ttl;
    }

    public String issue(Long userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** @throws JwtException if the token is invalid or expired */
    public AuthUser verify(String token) {
        Claims claims = Jwts.parser().verifyWith(key).requireIssuer(ISSUER).clockSkewSeconds(30)
                .build().parseSignedClaims(token).getPayload();
        return new AuthUser(Long.valueOf(claims.getSubject()), claims.get("username", String.class));
    }

    /** Accepts "Bearer xxx" or a bare token; returns null for anything unusable. */
    public AuthUser verifyHeader(String header) {
        if (header == null || header.isBlank()) return null;
        String token = header.startsWith("Bearer ") ? header.substring(7) : header;
        try {
            return verify(token.trim());
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
