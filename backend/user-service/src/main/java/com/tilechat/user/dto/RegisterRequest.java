package com.tilechat.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Username is required")
        @Pattern(regexp = "^[a-zA-Z0-9_.]{3,32}$", message = "Username must be 3-32 letters, digits, '_' or '.'")
        String username,
        @NotBlank(message = "Display name is required")
        @Size(max = 40, message = "Display name must be at most 40 characters")
        String displayName,
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
        String password) {
}
