package com.tilechat.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tilechat.common.events.EventTopics;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxWriter {

    private final OutboxRepository repository;
    private final ObjectMapper mapper;

    public OutboxWriter(OutboxRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /** Must be called inside the business transaction (MANDATORY enforces it). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String routingKey, String eventId, Object event) {
        try {
            repository.save(new OutboxEvent(eventId, EventTopics.EXCHANGE, routingKey, mapper.writeValueAsString(event)));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Event is not serialisable", e);
        }
    }
}
