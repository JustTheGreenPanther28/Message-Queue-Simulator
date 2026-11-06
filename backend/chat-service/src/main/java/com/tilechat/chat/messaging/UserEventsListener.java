package com.tilechat.chat.messaging;

import com.tilechat.chat.config.RabbitConfig;
import com.tilechat.chat.realtime.RealtimeEvents.ProfileChanged;
import com.tilechat.chat.repository.ConversationMemberRepository;
import com.tilechat.chat.service.UserDirectory;
import com.tilechat.common.consumer.IdempotentConsumer;
import com.tilechat.common.events.UserProfileEvent;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the local profile replica in sync. Exactly-once effect on top of at-least-once
 * delivery: eventId dedup + version check. Failures are retried with backoff
 * (application.yml), then dead-lettered.
 */
@Component
public class UserEventsListener {

    private static final String CONSUMER = "chat.user-profiles";

    private final IdempotentConsumer idempotency;
    private final UserDirectory directory;
    private final ConversationMemberRepository members;
    private final ApplicationEventPublisher events;

    public UserEventsListener(IdempotentConsumer idempotency, UserDirectory directory,
                              ConversationMemberRepository members, ApplicationEventPublisher events) {
        this.idempotency = idempotency;
        this.directory = directory;
        this.members = members;
        this.events = events;
    }

    @RabbitListener(queues = RabbitConfig.USER_EVENTS_QUEUE)
    @Transactional
    public void on(UserProfileEvent event) {
        if (!idempotency.firstTime(CONSUMER, event.eventId())) return;
        directory.apply(event).ifPresent(profile ->
                // Tell everyone who chats with this user, so names/avatars update live.
                events.publishEvent(new ProfileChanged(profile, members.findContactIds(event.userId()))));
    }
}
