package com.tilechat.common.security;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

/**
 * Authenticates STOMP sessions with the JWT sent in the CONNECT frame, and only lets
 * clients subscribe to their own {@code /user/queue/...} destinations.
 */
public class StompAuthInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;

    public StompAuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) return message;

        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT) {
            AuthUser user = jwtService.verifyHeader(accessor.getFirstNativeHeader("Authorization"));
            if (user == null) throw new MessageDeliveryException("Invalid or missing token");
            accessor.setUser(user);
        } else if (command == StompCommand.SUBSCRIBE) {
            String dest = accessor.getDestination();
            if (accessor.getUser() == null || dest == null || !dest.startsWith("/user/queue/")) {
                throw new MessageDeliveryException("Subscription not allowed: " + dest);
            }
        } else if (command == StompCommand.SEND && accessor.getUser() == null) {
            throw new MessageDeliveryException("Not authenticated");
        }
        return message;
    }
}
