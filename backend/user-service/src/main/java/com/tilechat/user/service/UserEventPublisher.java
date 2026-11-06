package com.tilechat.user.service;

import com.tilechat.common.events.EventTopics;
import com.tilechat.common.events.UserProfileEvent;
import com.tilechat.common.outbox.OutboxWriter;
import com.tilechat.user.domain.User;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Turns profile changes into outbox events (published reliably by the relay). */
@Component
public class UserEventPublisher {

    private final OutboxWriter outbox;

    public UserEventPublisher(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void registered(User user) {
        publish(EventTopics.USER_REGISTERED, user);
    }

    public void updated(User user) {
        publish(EventTopics.USER_UPDATED, user);
    }

    private void publish(String routingKey, User u) {
        String eventId = UUID.randomUUID().toString();
        outbox.enqueue(routingKey, eventId, new UserProfileEvent(eventId, u.getId(), u.getUsername(),
                u.getDisplayName(), u.getAvatarKey(), u.getVersion(), Instant.now()));
    }
}
