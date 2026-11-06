package com.tilechat.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public final class AvatarRequests {
    private AvatarRequests() {}

    public record UploadUrl(@NotBlank(message = "contentType is required") String contentType,
                            @NotNull @Positive(message = "size must be positive") Long size) {}

    public record SetAvatar(@NotBlank(message = "uploadKey is required") String uploadKey) {}
}
