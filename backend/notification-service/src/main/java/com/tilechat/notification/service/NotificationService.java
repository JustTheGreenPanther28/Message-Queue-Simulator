package com.tilechat.notification.service;

import com.tilechat.common.events.MembersAddedEvent;
import com.tilechat.common.events.MessageCreatedEvent;
import com.tilechat.common.storage.ObjectStorage;
import com.tilechat.notification.domain.Notification;
import com.tilechat.notification.dto.NotificationDto;
import com.tilechat.notification.dto.NotificationPage;
import com.tilechat.notification.repository.NotificationRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;

@Service
public class NotificationService {

    /** Published in-transaction; pushed over WebSocket after commit. */
    public record Created(Long userId, NotificationDto notification) {}

    private final NotificationRepository repository;
    private final NotificationFactory factory;
    private final ApplicationEventPublisher events;
    private final ObjectStorage storage;

    public NotificationService(NotificationRepository repository, NotificationFactory factory,
                               ApplicationEventPublisher events, ObjectStorage storage) {
        this.storage = storage;
        this.repository = repository;
        this.factory = factory;
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onMessageCreated(MessageCreatedEvent e) {
        new LinkedHashSet<>(e.recipientIds()).forEach(uid -> create(uid, factory.newMessage(e, uid)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onMembersAdded(MembersAddedEvent e) {
        new LinkedHashSet<>(e.addedUserIds()).forEach(uid -> create(uid, factory.addedToGroup(e, uid)));
    }

    private void create(Long userId, Notification n) {
        // Second line of defence after eventId dedup; the unique constraint is the last.
        if (repository.existsByUserIdAndSourceKey(userId, n.getSourceKey())) return;
        Notification saved = repository.save(n);
        events.publishEvent(new Created(userId, toDto(saved)));
    }

    private NotificationDto toDto(Notification n) {
        return new NotificationDto(n.getId(), n.getType(), n.getConversationId(), n.getTitle(), n.getBody(),
                storage.urlFor(n.getImageKey()), n.isRead(), n.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public NotificationPage list(Long userId, int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        return new NotificationPage(
                repository.findByUserIdOrderByIdDesc(userId, PageRequest.of(0, size)).stream().map(this::toDto).toList(),
                repository.countByUserIdAndReadFalse(userId));
    }

    @Transactional
    public void markAllRead(Long userId) {
        repository.markAllRead(userId);
    }

    @Transactional
    public void markConversationRead(Long userId, Long conversationId) {
        repository.markConversationRead(userId, conversationId);
    }
}
