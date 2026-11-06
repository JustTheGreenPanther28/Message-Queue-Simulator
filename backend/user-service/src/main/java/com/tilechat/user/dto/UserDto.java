package com.tilechat.user.dto;

/** avatarUrl is a short-lived presigned URL. */
public record UserDto(Long id, String username, String displayName, String avatarUrl, long version) {
}
