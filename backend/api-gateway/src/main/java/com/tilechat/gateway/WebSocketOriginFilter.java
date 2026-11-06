package com.tilechat.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;

/**
 * Blocks cross-site WebSocket hijacking at the edge. Browsers always send Origin on a WebSocket
 * handshake, and the gateway opens its own connection to the service (without the browser's
 * Origin), so this is the place to check it. Requests without Origin aren't from a browser page
 * and carry no ambient credentials (the JWT is sent in the STOMP CONNECT frame), so they pass.
 */
@Component
public class WebSocketOriginFilter implements GlobalFilter, Ordered {

    private final List<String> allowed;

    public WebSocketOriginFilter(@Value("${app.websocket.allowed-origins:*}") String[] allowed) {
        this.allowed = Arrays.stream(allowed).map(String::trim).toList();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String origin = headers.getOrigin();
        boolean upgrade = "websocket".equalsIgnoreCase(headers.getUpgrade());
        if (upgrade && origin != null && !allowed.contains("*") && !allowed.contains(origin)) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
