package com.tilechat.user.dto;

public record AuthResponse(String token, UserDto user) {
}
