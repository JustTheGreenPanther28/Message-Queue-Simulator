package com.tilechat.common.storage;

import java.util.Map;
import java.util.Set;

/** Image type detection from magic bytes. The client's file name and content type are never trusted. */
public final class ImageTypes {
    private ImageTypes() {}

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    public static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
    private static final Map<String, String> MIME = Map.of("png", "image/png", "jpg", "image/jpeg", "gif", "image/gif", "webp", "image/webp");

    /** @return png | jpg | gif | webp, or null if the bytes aren't one of those */
    public static String detectExtension(byte[] h) {
        if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return "png";
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) return "jpg";
        if (h.length >= 4 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8') return "gif";
        if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') return "webp";
        return null;
    }

    public static String mimeOf(String extension) {
        return MIME.get(extension);
    }
}
