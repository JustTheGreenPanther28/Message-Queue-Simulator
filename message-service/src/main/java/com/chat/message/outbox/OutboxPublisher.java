package com.chat.message.outbox;

import com.chat.message.config.RabbitConfig;
import com.chat.message.model.OutboxEvent;
import com.chat.message.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Transactional outbox relay.
 *
 * Problem it solves: "save message to DB" and "publish to RabbitMQ" are two
 * different systems — we can't commit both atomically. If we published inside
 * the request and the broker was down (or the app crashed in between), the
 * notification would be silently lost.
 *
 * Instead the request writes the event to the outbox table in the SAME DB
 * transaction as the message. This poller publishes pending rows and marks
 * them done. Guarantee: at-least-once delivery (a crash after publish but
 * before marking can re-send), so the consumer must be idempotent.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outbox;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate tx;
    private final int batchSize;

    public OutboxPublisher(OutboxRepository outbox,
                           RabbitTemplate rabbitTemplate,
                           TransactionTemplate tx,
                           @Value("${app.outbox.batch-size}") int batchSize) {
        this.outbox = outbox;
        this.rabbitTemplate = rabbitTemplate;
        this.tx = tx;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms}")
    public void publishPending() {
        tx.executeWithoutResult(status -> {
            List<OutboxEvent> batch = outbox.lockUnpublishedBatch(batchSize);
            for (OutboxEvent event : batch) {
                try {
                    publishWithConfirm(event);
                    outbox.markPublished(event.id());
                } catch (Exception e) {
                    // Broker down or no ack: stop here. Rows already marked in this
                    // batch are committed; the rest stay pending for the next run.
                    log.warn("Outbox publish failed at event {}, will retry: {}", event.id(), e.getMessage());
                    break;
                }
            }
            if (!batch.isEmpty()) {
                log.debug("Outbox processed batch of {}", batch.size());
            }
        });
    }

    private void publishWithConfirm(OutboxEvent event) {
        Message amqpMessage = MessageBuilder
                .withBody(event.payload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId("outbox-" + event.id())
                .build();

        // Wait for the broker to confirm it has the message before we mark the
        // row as published. Without this, "sent" could still mean "lost".
        rabbitTemplate.invoke(ops -> {
            ops.send(RabbitConfig.CHAT_EVENTS_EXCHANGE, event.eventType(), amqpMessage);
            ops.waitForConfirmsOrDie(5_000);
            return null;
        });
    }
}
