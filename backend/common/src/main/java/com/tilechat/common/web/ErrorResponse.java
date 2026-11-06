package com.tilechat.common.web;

import org.springframework.http.HttpStatus;

import java.time.Instant;

public record ErrorResponse(int status, String error, String message, Instant timestamp) {
    public static ErrorResponse of(HttpStatus status, String message) {
        return new ErrorResponse(status.value(), status.getReasonPhrase(), message, Instant.now());
    }
}
