package com.tilechat.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code version} is the profile version the client last saw (optimistic concurrency). */
public record UpdateProfileRequest(
        @NotBlank(message = "Display name is required")
        @Size(max = 40, message = "Display name must be at most 40 characters")
        String displayName,
        @NotNull(message = "version is required") Long version) {
}
