package com.chat.notification.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topology owned by the consumer:
 *
 *   chat.events (topic) --message.created--> notification.message-created   (main)
 *                                                  |  on failure, listener republishes to:
 *                                                  v
 *                          notification.message-created.retry  (TTL = delay, then dead-letters back to main)
 *                                                  |  after max attempts:
 *                                                  v
 *                          notification.message-created.dlq    (parked for manual inspection)
 */
@Configuration
public class RabbitConfig {

    public static final String CHAT_EVENTS_EXCHANGE = "chat.events";
    public static final String MESSAGE_CREATED_ROUTING_KEY = "message.created";

    public static final String MAIN_QUEUE = "notification.message-created";
    public static final String RETRY_QUEUE = "notification.message-created.retry";
    public static final String DLQ = "notification.message-created.dlq";

    @Value("${app.retry.delay-ms}")
    private int retryDelayMs;

    @Bean
    public TopicExchange chatEventsExchange() {
        return new TopicExchange(CHAT_EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public Queue mainQueue() {
        // If a message is ever rejected (exception escaped the listener),
        // it goes to the DLQ via the default exchange instead of being lost.
        return QueueBuilder.durable(MAIN_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(DLQ)
                .build();
    }

    @Bean
    public Queue retryQueue() {
        // Nobody consumes this queue. Messages sit here for retryDelayMs,
        // expire, and are dead-lettered back to the main queue = delayed retry.
        return QueueBuilder.durable(RETRY_QUEUE)
                .ttl(retryDelayMs)
                .deadLetterExchange("")
                .deadLetterRoutingKey(MAIN_QUEUE)
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Binding mainBinding() {
        return BindingBuilder.bind(mainQueue()).to(chatEventsExchange()).with(MESSAGE_CREATED_ROUTING_KEY);
    }
}
