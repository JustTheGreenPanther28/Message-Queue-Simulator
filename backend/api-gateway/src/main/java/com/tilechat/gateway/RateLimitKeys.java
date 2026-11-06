package com.tilechat.gateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Who a request counts against for rate limiting.
 * <ul>
 *   <li>Signed-in traffic: the user (from a JWT the gateway verifies, so nobody can spread load
 *       over made-up user ids). People sharing an IP (office NAT, mobile carrier) don't
 *       throttle each other.</li>
 *   <li>Anonymous traffic and WebSocket handshakes (the token is sent later, in STOMP CONNECT):
 *       the client IP.</li>
 *   <li>Login/register: always the IP, in a separate, stricter bucket against password guessing.</li>
 * </ul>
 */
@Configuration
public class RateLimitKeys {

    private final SecretKey key;

    public RateLimitKeys(@Value("${app.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Client IP. Uses the first X-Forwarded-For entry, which our nginx overwrites with the real
     * peer address. Never expose the gateway directly without a proxy that does the same.
     */
    static String clientIp(ServerWebExchange exchange) {
        return Optional.ofNullable(exchange.getRequest().getHeaders().getFirst("X-Forwarded-For"))
                .map(h -> h.split(",")[0].trim())
                .or(() -> Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                        .map(InetSocketAddress::getAddress).map(a -> a.getHostAddress()))
                .orElse("unknown");
    }

    Optional<String> verifiedUser(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) return Optional.empty();
        try {
            return Optional.of(Jwts.parser().verifyWith(key).requireIssuer("tilechat").build()
                    .parseSignedClaims(header.substring(7).trim()).getPayload().getSubject());
        } catch (Exception invalid) {
            return Optional.empty(); // the service will answer 401; count it against the IP
        }
    }

    @Bean
    @Primary
    public KeyResolver apiKeyResolver() {
        return exchange -> Mono.just(verifiedUser(exchange).map(u -> "api:user:" + u)
                .orElseGet(() -> "api:ip:" + clientIp(exchange)));
    }

    @Bean
    public KeyResolver authKeyResolver() {
        return exchange -> Mono.just("auth:ip:" + clientIp(exchange));
    }
}
