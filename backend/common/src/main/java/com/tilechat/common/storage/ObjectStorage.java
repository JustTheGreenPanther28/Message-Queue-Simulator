package com.tilechat.common.storage;

import com.tilechat.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Private object storage behind presigned URLs. The bucket is never public.
 *
 * <ol>
 *   <li><b>Upload:</b> {@link #presignUpload} gives the browser a short-lived PUT URL for
 *       {@code tmp/<owner>/<uuid>}. Content type and exact size are part of the signature.</li>
 *   <li><b>Attach:</b> {@link #promote} checks the upload belongs to the caller, is at most
 *       10 MB and really is an image (magic bytes), then copies it to its final key. The
 *       final key is derived from the upload id, so a retried request writes the same object.
 *       The caller deletes the tmp object after its DB transaction commits.</li>
 *   <li><b>Read:</b> {@link #urlFor} returns a presigned GET URL. URLs are reused while they have
 *       more than half their lifetime left, so browsers can cache the image.</li>
 * </ol>
 * Uploads that are never attached are removed by {@link #sweepAbandonedUploads}.
 */
public class ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorage.class);
    public static final String TMP_PREFIX = "tmp/";
    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final Pattern UPLOAD_ID = Pattern.compile("[0-9a-f-]{36}");
    private static final int URL_CACHE_SIZE = 20_000;

    private record SignedUrl(String url, Instant expiresAt) {}

    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageProperties props;
    private final Map<String, SignedUrl> urlCache = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SignedUrl> eldest) {
            return size() > URL_CACHE_SIZE;
        }
    });

    public ObjectStorage(S3Client s3, S3Presigner presigner, StorageProperties props) {
        this.s3 = s3;
        this.presigner = presigner;
        this.props = props;
    }

    // ---------------------------------------------------------------- upload

    public PresignedUpload presignUpload(String owner, String contentType, long size) {
        if (owner == null || !OWNER.matcher(owner).matches()) throw new IllegalArgumentException("bad owner");
        if (contentType == null || !ImageTypes.ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw ApiException.badRequest("Only PNG, JPEG, GIF or WEBP images are allowed");
        }
        if (size <= 0 || size > ImageTypes.MAX_BYTES) throw ApiException.badRequest("Image must be 10 MB or smaller");

        String key = TMP_PREFIX + owner + "/" + UUID.randomUUID();
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(props.bucket()).key(key).contentType(contentType).contentLength(size).build();
        PresignedPutObjectRequest signed = presigner.presignPutObject(r -> r.signatureDuration(props.uploadUrlTtl()).putObjectRequest(put));

        Map<String, String> headers = new LinkedHashMap<>();
        signed.signedHeaders().forEach((name, values) -> {
            // The browser adds these itself (and may not set them manually).
            if (!name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("content-length")) {
                headers.put(name, String.join(",", values));
            }
        });
        return new PresignedUpload(key, publicUrl(signed.url()), "PUT", headers, signed.expiration());
    }

    /**
     * Validates an upload made by {@code owner} and copies it to {@code destPrefix}.
     * @return the final object key
     */
    public String promote(String uploadKey, String owner, String destPrefix) {
        try {
            return doPromote(uploadKey, owner, destPrefix);
        } catch (SdkClientException e) {
            // Storage unreachable: a 503 tells clients this is temporary and worth retrying.
            log.warn("Object storage unavailable: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Image storage is temporarily unavailable. Try again shortly.");
        }
    }

    private String doPromote(String uploadKey, String owner, String destPrefix) {
        String ownPrefix = TMP_PREFIX + owner + "/";
        if (uploadKey == null || !uploadKey.startsWith(ownPrefix)
                || !UPLOAD_ID.matcher(uploadKey.substring(ownPrefix.length())).matches()) {
            throw ApiException.badRequest("Unknown upload");
        }
        String bucket = props.bucket();
        HeadObjectResponse head;
        try {
            head = s3.headObject(r -> r.bucket(bucket).key(uploadKey));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) throw ApiException.badRequest("Upload not found or expired. Please upload the image again.");
            throw e;
        }
        if (head.contentLength() > ImageTypes.MAX_BYTES) {
            deleteQuietly(uploadKey);
            throw ApiException.badRequest("Image must be 10 MB or smaller");
        }
        ResponseBytes<GetObjectResponse> first = s3.getObjectAsBytes(r -> r.bucket(bucket).key(uploadKey).range("bytes=0-11"));
        String ext = ImageTypes.detectExtension(first.asByteArray());
        if (ext == null) {
            deleteQuietly(uploadKey);
            throw ApiException.badRequest("Only PNG, JPEG, GIF or WEBP images are allowed");
        }
        String finalKey = destPrefix + "/" + uploadKey.substring(ownPrefix.length()) + "." + ext;
        s3.copyObject(r -> r.sourceBucket(bucket).sourceKey(uploadKey)
                .destinationBucket(bucket).destinationKey(finalKey)
                .metadataDirective(MetadataDirective.REPLACE)
                .contentType(ImageTypes.mimeOf(ext))
                .cacheControl("private, max-age=43200"));
        return finalKey;
    }

    // ---------------------------------------------------------------- read

    /** Presigned GET URL, or null for a null key. */
    public String urlFor(String key) {
        if (key == null || key.isBlank()) return null;
        Instant now = Instant.now();
        SignedUrl cached = urlCache.get(key);
        if (cached != null && cached.expiresAt().isAfter(now.plus(props.downloadUrlTtl().dividedBy(2)))) {
            return cached.url();
        }
        PresignedGetObjectRequest signed = presigner.presignGetObject(r -> r
                .signatureDuration(props.downloadUrlTtl())
                .getObjectRequest(g -> g.bucket(props.bucket()).key(key)));
        SignedUrl fresh = new SignedUrl(publicUrl(signed.url()), signed.expiration());
        urlCache.put(key, fresh);
        return fresh.url();
    }

    /**
     * Proxy mode ({@code public-endpoint} is a path like "/storage"): the URL is signed for the
     * internal storage host, and the browser gets a same-origin path. The reverse proxy forwards
     * /storage/... to storage with the internal Host header, so the signature still matches.
     * No CORS, no second origin, works from any hostname. Direct mode returns the URL as signed.
     */
    private String publicUrl(java.net.URL signed) {
        String pub = props.publicEndpoint();
        if (pub == null || !pub.startsWith("/")) return signed.toString();
        String prefix = pub.endsWith("/") ? pub.substring(0, pub.length() - 1) : pub;
        return prefix + signed.getPath() + (signed.getQuery() == null ? "" : "?" + signed.getQuery());
    }

    // ---------------------------------------------------------------- housekeeping

    public void deleteQuietly(String key) {
        if (key == null) return;
        try {
            s3.deleteObject(r -> r.bucket(props.bucket()).key(key));
            urlCache.remove(key);
        } catch (Exception e) {
            log.warn("Could not delete object {}: {}", key, e.getMessage());
        }
    }

    /** Deletes uploads that were never attached. Portable: works on any S3 implementation. */
    public int sweepAbandonedUploads() {
        Instant cutoff = Instant.now().minus(props.abandonedUploadTtl());
        int removed = 0;
        for (S3Object o : s3.listObjectsV2Paginator(r -> r.bucket(props.bucket()).prefix(TMP_PREFIX)).contents()) {
            if (o.lastModified().isBefore(cutoff)) {
                deleteQuietly(o.key());
                removed++;
            }
        }
        return removed;
    }

    /** Local dev only: create the bucket and a CORS rule so browsers can PUT directly. */
    public void initBucket() {
        String bucket = props.bucket();
        try {
            s3.headBucket(r -> r.bucket(bucket));
        } catch (S3Exception e) {
            try {
                s3.createBucket(r -> r.bucket(bucket));
                log.info("Created bucket {}", bucket);
            } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException ignored) {
                // another service won the race
            }
        }
        try {
            s3.putBucketCors(r -> r.bucket(bucket).corsConfiguration(c -> c.corsRules(CORSRule.builder()
                    .allowedOrigins(props.corsOrigins())
                    .allowedMethods("GET", "PUT", "HEAD")
                    .allowedHeaders("*")
                    .exposeHeaders("ETag")
                    .maxAgeSeconds(3600)
                    .build())));
        } catch (Exception e) {
            log.warn("Could not set bucket CORS (set it on the bucket manually): {}", e.getMessage());
        }
    }

    public Duration downloadUrlTtl() {
        return props.downloadUrlTtl();
    }
}
