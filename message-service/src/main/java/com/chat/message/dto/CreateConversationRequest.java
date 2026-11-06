package com.chat.message.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateConversationRequest(
        @NotBlank @Size(max = 100) String name,
        @NotEmpty List<Long> memberIds
) {
}
