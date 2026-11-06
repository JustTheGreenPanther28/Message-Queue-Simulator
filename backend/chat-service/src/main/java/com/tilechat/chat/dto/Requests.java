package com.tilechat.chat.dto;

import jakarta.validation.constraints.*;

import java.util.List;

/** Request bodies, grouped because they're tiny. */
public final class Requests {
    private Requests() {}

    /** Text, an image (uploadKey from a presigned upload), or an image with a caption. */
    public record SendMessage(
            @NotBlank(message = "clientMessageId is required")
            @Size(max = 64, message = "clientMessageId is too long") String clientMessageId,
            @Size(max = 4000, message = "Message must be at most 4000 characters") String content,
            @Size(max = 200) String uploadKey) {}

    public record UploadUrl(@NotBlank(message = "contentType is required") String contentType,
                            @NotNull @Positive(message = "size must be positive") Long size) {}

    public record OpenDirect(@NotNull(message = "userId is required") Long userId) {}

    public record CreateGroup(
            @NotBlank(message = "Group name is required")
            @Size(max = 64, message = "Group name must be at most 64 characters") String name,
            @NotEmpty(message = "Pick at least one member")
            @Size(max = 99, message = "A group can have at most 100 members") List<@NotNull Long> memberIds) {}

    public record AddMembers(@NotEmpty(message = "Pick at least one member") @Size(max = 99) List<@NotNull Long> userIds) {}

    /** messageIds null/empty = "everything I've synced". */
    public record Delivered(@Size(max = 500) List<@NotNull Long> messageIds) {}
}
