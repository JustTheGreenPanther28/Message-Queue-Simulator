package com.tilechat.common.consumer;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Call {@link #firstTime} at the start of the listener's transaction. The marker row and
 * the business change commit together, so an event is applied exactly once even though
 * RabbitMQ delivers at least once. Two concurrent duplicates race on the primary key:
 * the loser's transaction fails, the message is retried, and then it is skipped.
 */
@Component
public class IdempotentConsumer {

    private final ProcessedEventRepository repository;

    public IdempotentConsumer(ProcessedEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTime(String consumerName, String eventId) {
        String key = consumerName + ":" + eventId;
        if (repository.existsById(key)) return false;
        repository.saveAndFlush(new ProcessedEvent(key));
        return true;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    @Transactional
    public void cleanup() {
        repository.deleteOlderThan(Instant.now().minus(Duration.ofDays(7)));
    }
}
