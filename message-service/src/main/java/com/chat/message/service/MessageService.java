package com.chat.message.service;

import com.chat.message.exception.ConflictException;
import com.chat.message.exception.ForbiddenException;
import com.chat.message.exception.NotFoundException;
import com.chat.message.model.Message;
import com.chat.message.repository.ConversationRepository;
import com.chat.message.repository.MessageRepository;
import com.chat.message.repository.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class MessageService {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final String MESSAGE_CREATED = "message.created";

    private final MessageRepository messages;
    private final ConversationRepository conversations;
    private final OutboxRepository outbox;
    private final ConversationService conversationService;
    private final MessageCacheService cache;
    private final ObjectMapper objectMapper;

    public MessageService(MessageRepository messages,
                          ConversationRepository conversations,
                          OutboxRepository outbox,
                          ConversationService conversationService,
                          MessageCacheService cache,
                          ObjectMapper objectMapper) {
        this.messages = messages;
        this.conversations = conversations;
        this.outbox = outbox;
        this.conversationService = conversationService;
        this.cache = cache;
        this.objectMapper = objectMapper;
    }

    /** created = false means this was a replay of an earlier request with the same idempotency key. */
    public record SendResult(Message message, boolean created) {
    }

    /**
     * Send flow (one DB transaction):
     *  1. Membership check.
     *  2. Idempotency fast path: same key already used -> return the existing message.
     *  3. Lock the conversation row and reserve the next seq (serialises concurrent senders).
     *  4. Insert the message; ON CONFLICT catches a duplicate that raced past step 2.
     *  5. Write a "message.created" event to the outbox table (same transaction).
     *  6. After commit: evict the Redis cache.
     */
    @Transactional
    public SendResult send(long conversationId, long senderId, String content, String idempotencyKey) {
        conversationService.requireExists(conversationId);
        conversationService.requireMember(conversationId, senderId);

        if (idempotencyKey != null) {
            Optional<Message> existing = messages.findByIdempotencyKey(conversationId, senderId, idempotencyKey);
            if (existing.isPresent()) {
                return new SendResult(existing.get(), false);
            }
        }

        long seq = conversations.reserveNextSeq(conversationId)
                .orElseThrow(() -> new NotFoundException("Conversation " + conversationId + " not found"));

        Optional<Message> inserted = messages.insert(conversationId, senderId, seq, content, idempotencyKey);
        if (inserted.isEmpty()) {
            // Two identical requests ran at the same time; the other one won.
            // Roll back so the seq we reserved is released, and return the winner's message.
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            Message winner = messages.findByIdempotencyKey(conversationId, senderId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Idempotent insert conflicted but no row found"));
            return new SendResult(winner, false);
        }

        Message message = inserted.get();
        outbox.insert(MESSAGE_CREATED, buildMessageCreatedPayload(message));
        evictCacheAfterCommit(conversationId);
        return new SendResult(message, true);
    }

    /**
     * Read flow (cache-aside): only the default "latest page" is cached,
     * because that is what every client loads when it opens a chat.
     * Older pages (beforeSeq != null) always go to the DB.
     */
    @Transactional(readOnly = true)
    public List<Message> list(long conversationId, long userId, Long beforeSeq, int limit) {
        conversationService.requireExists(conversationId);
        conversationService.requireMember(conversationId, userId);

        boolean cacheable = beforeSeq == null && limit == DEFAULT_PAGE_SIZE;
        if (cacheable) {
            Optional<List<Message>> cached = cache.get(conversationId);
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        List<Message> newestFirst = beforeSeq == null
                ? messages.findLatest(conversationId, limit)
                : messages.findBefore(conversationId, beforeSeq, limit);
        List<Message> result = new ArrayList<>(newestFirst);
        Collections.reverse(result); // return oldest -> newest, like a chat window

        if (cacheable) {
            cache.put(conversationId, result);
        }
        return result;
    }

    @Transactional
    public Message edit(long messageId, long userId, String content, long expectedVersion) {
        Message current = messages.findById(messageId)
                .orElseThrow(() -> new NotFoundException("Message " + messageId + " not found"));
        if (!current.senderId().equals(userId)) {
            throw new ForbiddenException("Only the sender can edit this message");
        }
        Message updated = messages.updateContent(messageId, content, expectedVersion)
                .orElseThrow(() -> new ConflictException(
                        "Message was modified by another request. Reload and retry (current version may have changed)."));
        evictCacheAfterCommit(updated.conversationId());
        return updated;
    }

    @Transactional
    public void delete(long messageId, long userId) {
        Message current = messages.findById(messageId)
                .orElseThrow(() -> new NotFoundException("Message " + messageId + " not found"));
        if (!current.senderId().equals(userId)) {
            throw new ForbiddenException("Only the sender can delete this message");
        }
        messages.softDelete(messageId);
        evictCacheAfterCommit(current.conversationId());
    }

    /**
     * Why after commit and not right away?
     * If we evicted before commit, a concurrent reader could miss the cache,
     * read the OLD rows from Postgres (our transaction isn't visible yet) and
     * put them back into Redis — leaving the cache stale until the TTL.
     */
    private void evictCacheAfterCommit(long conversationId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.evict(conversationId);
                }
            });
        } else {
            cache.evict(conversationId);
        }
    }

    private String buildMessageCreatedPayload(Message message) {
        List<Long> recipients = new ArrayList<>(conversationService.memberIds(message.conversationId()));
        recipients.remove(message.senderId());

        String preview = message.content().length() > 100
                ? message.content().substring(0, 100) + "..."
                : message.content();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", message.id());
        payload.put("conversationId", message.conversationId());
        payload.put("senderId", message.senderId());
        payload.put("seq", message.seq());
        payload.put("preview", preview);
        payload.put("recipientIds", recipients);
        payload.put("createdAt", message.createdAt());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise event", e);
        }
    }
}
