package com.chat.message.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The client sends back the version it last saw. If someone else edited the
 * message in between, the versions won't match and the update is rejected (409).
 */
public record UpdateMessageRequest(
        @NotBlank @Size(max = 4000) String content,
        @NotNull Long version
) {
}
