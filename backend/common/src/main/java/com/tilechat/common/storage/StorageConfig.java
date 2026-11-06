package com.tilechat.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/** Import into any service that stores or shows images. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    private final StorageProperties props;

    public StorageConfig(StorageProperties props) {
        this.props = props;
    }

    private AwsCredentialsProvider credentials() {
        if (props.accessKey() == null || props.accessKey().isBlank()) return DefaultCredentialsProvider.create();
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
    }

    private static boolean set(String s) {
        return s != null && !s.isBlank();
    }

    @Bean(destroyMethod = "close")
    public S3Client s3Client() {
        var builder = S3Client.builder().region(Region.of(props.region())).credentialsProvider(credentials());
        if (set(props.endpoint())) {
            builder.endpointOverride(URI.create(props.endpoint())).forcePathStyle(true);
        }
        return builder.build();
    }

    /** Signs for the host the browser uses, which may differ from the one the service uses. */
    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner() {
        var builder = S3Presigner.builder().region(Region.of(props.region())).credentialsProvider(credentials())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(set(props.endpoint())).build());
        // Absolute public endpoint: sign for that host (direct mode). A path ("/storage"): sign for
        // the internal host; ObjectStorage turns it into a same-origin URL (proxy mode).
        String endpoint = set(props.publicEndpoint()) && !props.publicEndpoint().startsWith("/")
                ? props.publicEndpoint() : props.endpoint();
        if (set(endpoint)) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }

    @Bean
    public ObjectStorage objectStorage(S3Client s3, S3Presigner presigner) {
        return new ObjectStorage(s3, presigner, props);
    }

    @Bean
    public StorageMaintenance storageMaintenance(ObjectStorage storage) {
        return new StorageMaintenance(storage, props.initBucket());
    }

    /** Bucket setup on startup (local dev) and the hourly sweep of abandoned uploads. */
    public static class StorageMaintenance {
        private final ObjectStorage storage;
        private final boolean initBucket;

        StorageMaintenance(ObjectStorage storage, boolean initBucket) {
            this.storage = storage;
            this.initBucket = initBucket;
        }

        @EventListener(ApplicationReadyEvent.class)
        public void onReady() {
            if (!initBucket) return;
            for (int attempt = 1; attempt <= 10; attempt++) {
                try {
                    storage.initBucket();
                    return;
                } catch (Exception e) {
                    log.warn("Object storage not ready (attempt {}): {}", attempt, e.getMessage());
                    try { Thread.sleep(2000L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }
        }

        @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
        public void sweep() {
            try {
                int n = storage.sweepAbandonedUploads();
                if (n > 0) log.info("Deleted {} abandoned uploads", n);
            } catch (Exception e) {
                log.warn("Upload sweep failed: {}", e.getMessage());
            }
        }
    }
}
