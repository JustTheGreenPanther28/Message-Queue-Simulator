package com.tilechat.notification.service;

import com.tilechat.common.events.MembersAddedEvent;
import com.tilechat.common.events.MessageCreatedEvent;
import com.tilechat.notification.domain.Notification;
import com.tilechat.notification.domain.NotificationType;
import org.springframework.stereotype.Component;

/** Turns integration events into user-facing notification text. */
@Component
public class NotificationFactory {

    public Notification newMessage(MessageCreatedEvent e, Long recipientId) {
        boolean group = "GROUP".equals(e.conversationType());
        boolean request = e.requestRecipientIds() != null && e.requestRecipientIds().contains(recipientId);
        NotificationType type = request ? NotificationType.MESSAGE_REQUEST : NotificationType.NEW_MESSAGE;
        String title = request ? e.senderName() + " wants to message you" : group ? e.conversationName() : e.senderName();
        String body = group ? e.senderName() + ": " + e.preview() : e.preview();
        return new Notification(recipientId, type, "message:" + e.messageId(), e.conversationId(), title, body, e.senderAvatarKey());
    }

    public Notification addedToGroup(MembersAddedEvent e, Long recipientId) {
        return new Notification(recipientId, NotificationType.ADDED_TO_GROUP, "added:" + e.eventId(),
                e.conversationId(), e.conversationName(), e.actorName() + " invited you to the group", e.actorAvatarKey());
    }
}
