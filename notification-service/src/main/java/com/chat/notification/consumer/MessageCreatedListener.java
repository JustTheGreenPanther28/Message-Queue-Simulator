package com.chat.notification.consumer;

import com.chat.notification.config.RabbitConfig;
import com.chat.notification.model.MessageCreatedEvent;
import com.chat.notification.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Retry policy, handled explicitly so it is easy to follow:
 *  - success                     -> message is acked (removed from queue)
 *  - failure, attempts < max     -> copy goes to the retry queue with x-retry-count + 1,
 *                                   comes back to the main queue after the delay
 *  - failure, attempts >= max    -> copy goes to the DLQ
 *  - unparseable JSON            -> straight to DLQ (retrying won't fix bad data)
 * In every case the listener returns normally, so the original delivery is acked.
 */
@Component
public class MessageCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(MessageCreatedListener.class);
    private static final String RETRY_HEADER = "x-retry-count";

    private final NotificationService notificationService;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final int maxAttempts;

    public MessageCreatedListener(NotificationService notificationService,
                                  RabbitTemplate rabbitTemplate,
                                  ObjectMapper objectMapper,
                                  @Value("${app.retry.max-attempts}") int maxAttempts) {
        this.notificationService = notificationService;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.maxAttempts = maxAttempts;
    }

    @RabbitListener(queues = RabbitConfig.MAIN_QUEUE)
    public void onMessage(Message amqpMessage) {
        MessageCreatedEvent event;
        try {
            event = objectMapper.readValue(amqpMessage.getBody(), MessageCreatedEvent.class);
        } catch (Exception e) {
            log.error("Unparseable event, sending to DLQ: {}", e.getMessage());
            sendTo(RabbitConfig.DLQ, amqpMessage, retryCount(amqpMessage), e);
            return;
        }

        try {
            notificationService.process(event);
        } catch (Exception e) {
            int retries = retryCount(amqpMessage);
            if (retries + 1 < maxAttempts) {
                log.warn("Processing message {} failed (attempt {}/{}), scheduling retry: {}",
                        event.messageId(), retries + 1, maxAttempts, e.getMessage());
                sendTo(RabbitConfig.RETRY_QUEUE, amqpMessage, retries + 1, e);
            } else {
                log.error("Processing message {} failed after {} attempts, moving to DLQ: {}",
                        event.messageId(), maxAttempts, e.getMessage());
                sendTo(RabbitConfig.DLQ, amqpMessage, retries, e);
            }
        }
    }

    private int retryCount(Message message) {
        Object value = message.getMessageProperties().getHeader(RETRY_HEADER);
        return value instanceof Number n ? n.intValue() : 0;
    }

    private void sendTo(String queue, Message original, int retryCount, Exception cause) {
        Message copy = MessageBuilder.fromMessage(original)
                .setHeader(RETRY_HEADER, retryCount)
                .setHeader("x-last-error", String.valueOf(cause.getMessage()))
                .build();
        // Default exchange ("") routes directly to the queue with that name.
        rabbitTemplate.send("", queue, copy);
    }
}
