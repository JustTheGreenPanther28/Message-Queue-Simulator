package com.chat.notification.service;

import com.chat.notification.model.MessageCreatedEvent;
import com.chat.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ThreadLocalRandom;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final double simulateFailureRate;

    public NotificationService(NotificationRepository repository,
                               @Value("${app.simulate-failure-rate}") double simulateFailureRate) {
        this.repository = repository;
        this.simulateFailureRate = simulateFailureRate;
    }

    /**
     * "Sending" a notification = recording it + logging. In a real system this
     * is where FCM/APNs/email would be called. All inserts are idempotent, so
     * processing the same event twice is harmless.
     */
    @Transactional
    public void process(MessageCreatedEvent event) {
        if (simulateFailureRate > 0 && ThreadLocalRandom.current().nextDouble() < simulateFailureRate) {
            throw new IllegalStateException("Simulated push-provider failure for message " + event.messageId());
        }

        for (Long recipientId : event.recipientIds()) {
            boolean inserted = repository.insertIfAbsent(
                    event.messageId(), event.conversationId(), event.senderId(), recipientId, event.preview());
            if (inserted) {
                log.info("Notified user {} about message {} in conversation {}",
                        recipientId, event.messageId(), event.conversationId());
            } else {
                log.info("Duplicate delivery skipped: user {} already notified for message {}",
                        recipientId, event.messageId());
            }
        }
    }
}
