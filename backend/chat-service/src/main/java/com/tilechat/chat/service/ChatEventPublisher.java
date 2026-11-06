package com.tilechat.chat.service;

import com.tilechat.chat.domain.Conversation;
import com.tilechat.chat.domain.Message;
import com.tilechat.chat.domain.MessageType;
import com.tilechat.chat.service.UserDirectory.Actor;
import com.tilechat.common.events.EventTopics;
import com.tilechat.common.events.MembersAddedEvent;
import com.tilechat.common.events.MessageCreatedEvent;
import com.tilechat.common.outbox.OutboxWriter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Integration events for other services, written to the outbox inside the business transaction. */
@Component
public class ChatEventPublisher {

    private static final int PREVIEW_LENGTH = 80;

    private final OutboxWriter outbox;

    public ChatEventPublisher(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void messageCreated(Conversation c, Message m, Actor sender, List<Long> recipients, List<Long> requestRecipients) {
        if (recipients.isEmpty()) return;
        String eventId = UUID.randomUUID().toString();
        outbox.enqueue(EventTopics.MESSAGE_CREATED, eventId, new MessageCreatedEvent(eventId, m.getId(), c.getId(),
                c.getType().name(), c.getName(), sender.id(), sender.name(), sender.avatarKey(),
                preview(m), List.copyOf(recipients), List.copyOf(requestRecipients), Instant.now()));
    }

    public void membersAdded(Conversation c, Actor actor, List<Long> added) {
        if (added.isEmpty()) return;
        String eventId = UUID.randomUUID().toString();
        outbox.enqueue(EventTopics.MEMBERS_ADDED, eventId, new MembersAddedEvent(eventId, c.getId(), c.getName(),
                actor.id(), actor.name(), actor.avatarKey(), List.copyOf(added), Instant.now()));
    }

    private static String preview(Message m) {
        String text = m.getContent() == null ? "" : m.getContent().strip();
        if (m.getType() == MessageType.IMAGE) text = text.isEmpty() ? "Sent a photo" : "Photo: " + text;
        return text.length() > PREVIEW_LENGTH ? text.substring(0, PREVIEW_LENGTH - 1) + "…" : text;
    }
}
