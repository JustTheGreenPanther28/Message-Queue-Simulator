package com.tilechat.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Publishes pending outbox rows. A row is marked published only after the broker
 * confirms it AND it was routed to at least one queue (mandatory + returns). So events
 * are never lost, even if the consumer has not declared its queue yet. Delivery is
 * at-least-once; consumers deduplicate by eventId.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH = 50;

    private final OutboxRepository repository;
    private final RabbitTemplate rabbit;

    public OutboxRelay(OutboxRepository repository, RabbitTemplate rabbit) {
        this.repository = repository;
        this.rabbit = rabbit;
        this.rabbit.setMandatory(true);
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:300}")
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = repository.lockNextBatch(BATCH);
        for (OutboxEvent event : batch) {
            try {
                publish(event);
                event.markPublished();
            } catch (Exception e) {
                event.markFailed(e.getClass().getSimpleName() + ": " + e.getMessage());
                log.warn("Outbox publish failed (attempt {}) for {} {}: {}",
                        event.getAttempts(), event.getRoutingKey(), event.getEventId(), e.getMessage());
            }
        }
    }

    private void publish(OutboxEvent event) throws Exception {
        Message message = MessageBuilder.withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("UTF-8")
                .setMessageId(event.getEventId())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        CorrelationData correlation = new CorrelationData(event.getEventId());
        rabbit.send(event.getExchange(), event.getRoutingKey(), message, correlation);

        CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
        if (!confirm.isAck()) {
            throw new IllegalStateException("Broker nacked: " + confirm.getReason());
        }
        if (correlation.getReturned() != null) {
            throw new IllegalStateException("Unroutable (no queue bound yet for '" + event.getRoutingKey() + "')");
        }
    }

    /** Keeps the table small; published rows are only needed for debugging. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    @Transactional
    public void cleanup() {
        int removed = repository.deletePublishedBefore(Instant.now().minus(Duration.ofDays(1)));
        if (removed > 0) log.info("Removed {} published outbox rows", removed);
    }
}
