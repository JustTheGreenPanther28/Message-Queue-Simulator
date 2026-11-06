package com.chat.message.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Message Service only knows about the exchange it publishes to.
 * Queues belong to the consumers (Notification Service), so the producer
 * stays unaware of who listens — that is the decoupling a queue gives us.
 */
@Configuration
public class RabbitConfig {

    public static final String CHAT_EVENTS_EXCHANGE = "chat.events";
    public static final String MESSAGE_CREATED_ROUTING_KEY = "message.created";

    @Bean
    public TopicExchange chatEventsExchange() {
        return new TopicExchange(CHAT_EVENTS_EXCHANGE, true, false);
    }
}
