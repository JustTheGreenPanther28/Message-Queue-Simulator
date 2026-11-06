package com.chat.message.model;

public record OutboxEvent(Long id, String eventType, String payload) {
}
