package com.tilechat.notification.messaging;

import com.tilechat.common.consumer.IdempotentConsumer;
import com.tilechat.common.events.MembersAddedEvent;
import com.tilechat.common.events.MessageCreatedEvent;
import com.tilechat.notification.config.RabbitConfig;
import com.tilechat.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Idempotent consumers. Each handler runs in one transaction that both records the
 * eventId and writes the notifications, so a redelivered event has no effect. On error,
 * Spring retries with backoff and finally dead-letters the message.
 */
@Component
public class ChatEventsListener {

    private static final Logger log = LoggerFactory.getLogger(ChatEventsListener.class);

    private final IdempotentConsumer idempotency;
    private final NotificationService notifications;
    private final double failureRate;

    public ChatEventsListener(IdempotentConsumer idempotency, NotificationService notifications,
                              @Value("${app.simulate-failure-rate:0}") double failureRate) {
        this.idempotency = idempotency;
        this.notifications = notifications;
        this.failureRate = failureRate;
    }

    @RabbitListener(queues = RabbitConfig.MESSAGE_QUEUE)
    @Transactional
    public void onMessageCreated(MessageCreatedEvent event) {
        maybeFail(event.eventId());
        if (!idempotency.firstTime("notification.message-created", event.eventId())) {
            log.debug("Skipping duplicate event {}", event.eventId());
            return;
        }
        notifications.onMessageCreated(event);
    }

    @RabbitListener(queues = RabbitConfig.MEMBERS_QUEUE)
    @Transactional
    public void onMembersAdded(MembersAddedEvent event) {
        maybeFail(event.eventId());
        if (!idempotency.firstTime("notification.members-added", event.eventId())) return;
        notifications.onMembersAdded(event);
    }

    private void maybeFail(String eventId) {
        if (failureRate > 0 && ThreadLocalRandom.current().nextDouble() < failureRate) {
            log.warn("Simulated failure for event {}", eventId);
            throw new IllegalStateException("Simulated failure");
        }
    }
}
