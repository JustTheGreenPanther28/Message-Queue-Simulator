package com.chat.message.model;

import java.util.List;

public record Conversation(Long id, String name, List<Long> memberIds) {
}
