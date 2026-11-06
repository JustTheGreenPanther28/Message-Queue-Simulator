package com.tilechat.common.storage;

import java.time.Instant;
import java.util.Map;

/**
 * Where and how the browser should PUT the file. {@code headers} must be sent exactly as
 * given (they are part of the signature); the browser sets Content-Length itself, and it
 * must equal the declared size, so the size limit is enforced by the signature too.
 */
public record PresignedUpload(String key, String uploadUrl, String method, Map<String, String> headers, Instant expiresAt) {
}
