package com.tilechat.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * S3-compatible object storage. Leave {@code endpoint} blank for AWS S3; set it for
 * SeaweedFS / MinIO / R2. {@code publicEndpoint} is how browsers reach storage:
 * <ul>
 *   <li>a path such as {@code /storage} (proxy mode, used by our nginx): same-origin, no CORS;</li>
 *   <li>an absolute URL such as {@code https://files.example.com} (direct mode, e.g. AWS S3):
 *       URLs are signed for that host and the bucket needs a CORS rule for the web origin.</li>
 * </ul>
 * Blank keys = the AWS default credential chain (IAM role, env vars ...).
 */
@ConfigurationProperties("app.storage")
public record StorageProperties(
        String endpoint,
        String publicEndpoint,
        @DefaultValue("us-east-1") String region,
        String accessKey,
        String secretKey,
        @DefaultValue("tilechat") String bucket,
        /** Create the bucket + CORS rule on startup (local dev). In production the bucket is provisioned separately. */
        @DefaultValue("false") boolean initBucket,
        @DefaultValue({"http://localhost:3000", "http://localhost:5173"}) List<String> corsOrigins,
        @DefaultValue("10m") Duration uploadUrlTtl,
        @DefaultValue("12h") Duration downloadUrlTtl,
        /** Uploads never attached to a message/profile are deleted after this long. */
        @DefaultValue("24h") Duration abandonedUploadTtl) {
}
