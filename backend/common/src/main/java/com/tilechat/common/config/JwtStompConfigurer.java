package com.tilechat.common.config;

import com.tilechat.common.security.StompAuthInterceptor;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Shared STOMP setup: one endpoint, an in-memory broker for per-user queues, heartbeats
 * so dead connections are noticed, and JWT authentication on CONNECT.
 * Subclass it with {@code @Configuration @EnableWebSocketMessageBroker}.
 */
public abstract class JwtStompConfigurer implements WebSocketMessageBrokerConfigurer {

    private final StompAuthInterceptor authInterceptor;

    /** Browser origins allowed to open the socket. "*" only for local dev; the prod profile requires a list. */
    @org.springframework.beans.factory.annotation.Value("${app.websocket.allowed-origins:*}")
    private String[] allowedOrigins;

    protected JwtStompConfigurer(StompAuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    protected abstract String endpoint();

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(endpoint()).setAllowedOriginPatterns(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        ThreadPoolTaskScheduler heartbeat = new ThreadPoolTaskScheduler();
        heartbeat.setPoolSize(1);
        heartbeat.setThreadNamePrefix("ws-heartbeat-");
        heartbeat.initialize();
        registry.enableSimpleBroker("/queue").setHeartbeatValue(new long[]{10_000, 10_000}).setTaskScheduler(heartbeat);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}
