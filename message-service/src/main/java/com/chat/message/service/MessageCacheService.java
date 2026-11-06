package com.chat.message.service;

import com.chat.message.model.Message;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Cache-aside cache for the latest page of messages in a conversation.
 *
 * Redis is treated as optional: every method swallows Redis errors and the
 * caller falls back to Postgres. A cache outage makes reads slower, never broken.
 */
@Service
public class MessageCacheService {

    private static final Logger log = LoggerFactory.getLogger(MessageCacheService.class);
    private static final TypeReference<List<Message>> LIST_TYPE = new TypeReference<>() {};

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public MessageCacheService(StringRedisTemplate redis,
                               ObjectMapper objectMapper,
                               @Value("${app.cache.recent-messages-ttl-seconds}") long ttlSeconds) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    private String key(long conversationId) {
        return "conv:" + conversationId + ":recent";
    }

    public Optional<List<Message>> get(long conversationId) {
        try {
            String json = redis.opsForValue().get(key(conversationId));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, LIST_TYPE));
        } catch (Exception e) {
            log.warn("Redis read failed for conversation {}, falling back to DB: {}", conversationId, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(long conversationId, List<Message> messages) {
        try {
            // TTL is a safety net: even if an eviction is ever missed, stale data expires.
            redis.opsForValue().set(key(conversationId), objectMapper.writeValueAsString(messages), ttl);
        } catch (Exception e) {
            log.warn("Redis write failed for conversation {}: {}", conversationId, e.getMessage());
        }
    }

    public void evict(long conversationId) {
        try {
            redis.delete(key(conversationId));
        } catch (Exception e) {
            log.warn("Redis evict failed for conversation {} (TTL will expire it): {}", conversationId, e.getMessage());
        }
    }
}
