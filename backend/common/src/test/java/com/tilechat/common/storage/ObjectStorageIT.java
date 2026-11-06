package com.tilechat.common.storage;

import com.tilechat.common.web.ApiException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** The whole presigned flow against a real S3 server (SeaweedFS), the way a browser uses it. */
@Testcontainers
class ObjectStorageIT {

    @Container
    static GenericContainer<?> s3server = new GenericContainer<>("chrislusf/seaweedfs:4.50")
            .withCopyFileToContainer(MountableFile.forHostPath("../../infra/seaweedfs/s3.json"), "/etc/seaweedfs/s3.json")
            .withCommand("server", "-dir=/data", "-s3", "-s3.port=8333", "-s3.config=/etc/seaweedfs/s3.json",
                    "-master.volumeSizeLimitMB=64", "-volume.max=0")
            .withExposedPorts(8333)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(90)));

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    static ObjectStorage storage;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void setUp() throws Exception {
        String endpoint = "http://" + s3server.getHost() + ":" + s3server.getMappedPort(8333);
        StorageProperties props = new StorageProperties(endpoint, endpoint, "us-east-1", "tilechat", "tilechat-local-secret",
                "test-bucket", true, List.of("http://localhost:3000"), Duration.ofMinutes(5), Duration.ofHours(1), Duration.ofHours(24));
        StorageConfig config = new StorageConfig(props);
        storage = config.objectStorage(config.s3Client(), config.s3Presigner());
        for (int i = 0; ; i++) { // SeaweedFS's S3 port opens slightly before it accepts requests
            try { storage.initBucket(); break; } catch (Exception e) { if (i > 20) throw e; Thread.sleep(1000); }
        }
    }

    private static int put(PresignedUpload up, byte[] body) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(up.uploadUrl())).PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        up.headers().forEach(req::header);
        return http.send(req.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static HttpResponse<byte[]> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void uploadPromoteAndReadThroughPresignedUrls() throws Exception {
        PresignedUpload up = storage.presignUpload("7", "image/png", PNG.length);
        assertThat(up.key()).startsWith("tmp/7/");
        assertThat(put(up, PNG)).isEqualTo(200);

        String key = storage.promote(up.key(), "7", "chat/1");
        assertThat(key).startsWith("chat/1/").endsWith(".png");
        assertThat(storage.promote(up.key(), "7", "chat/1")).as("retry gives the same key").isEqualTo(key);

        HttpResponse<byte[]> res = get(storage.urlFor(key));
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo(PNG);
        assertThat(res.headers().firstValue("content-type")).hasValue("image/png");
        assertThat(storage.urlFor(key)).as("URL reused so browsers can cache it").isEqualTo(storage.urlFor(key));
    }

    @Test
    void objectsArePrivateAndSignaturesCannotBeTampered() throws Exception {
        PresignedUpload up = storage.presignUpload("8", "image/png", PNG.length);
        put(up, PNG);
        String key = storage.promote(up.key(), "8", "avatars/8");
        String url = storage.urlFor(key);

        String unsigned = url.substring(0, url.indexOf('?'));
        assertThat(get(unsigned).statusCode()).as("no anonymous access").isIn(401, 403);
        String otherKey = url.replace(key, key.replace(".png", "x.png"));
        assertThat(get(otherKey).statusCode()).as("signature is bound to the key").isIn(401, 403);
    }

    @Test
    void proxyModeGivesSameOriginPathsSignedForTheInternalHost() throws Exception {
        String endpoint = "http://" + s3server.getHost() + ":" + s3server.getMappedPort(8333);
        StorageProperties props = new StorageProperties(endpoint, "/storage", "us-east-1", "tilechat", "tilechat-local-secret",
                "test-bucket", false, List.of(), Duration.ofMinutes(5), Duration.ofHours(1), Duration.ofHours(24));
        StorageConfig config = new StorageConfig(props);
        ObjectStorage proxied = config.objectStorage(config.s3Client(), config.s3Presigner());

        PresignedUpload up = proxied.presignUpload("20", "image/png", PNG.length);
        assertThat(up.uploadUrl()).startsWith("/storage/test-bucket/tmp/20/").contains("X-Amz-Signature=");
        // What the reverse proxy does: strip the prefix and send it to storage's own host.
        PresignedUpload asProxied = new PresignedUpload(up.key(), endpoint + up.uploadUrl().substring("/storage".length()),
                up.method(), up.headers(), up.expiresAt());
        assertThat(put(asProxied, PNG)).isEqualTo(200);
        String key = proxied.promote(up.key(), "20", "chat/9");
        String url = proxied.urlFor(key);
        assertThat(url).startsWith("/storage/test-bucket/chat/9/");
        assertThat(get(endpoint + url.substring("/storage".length())).statusCode()).isEqualTo(200);
    }

    @Test
    void uploadMustMatchTheSignedSize() throws Exception {
        PresignedUpload up = storage.presignUpload("9", "image/png", PNG.length);
        byte[] bigger = new byte[PNG.length + 100];
        System.arraycopy(PNG, 0, bigger, 0, PNG.length);
        assertThat(put(up, bigger)).as("Content-Length is part of the signature").isIn(400, 403);
    }

    @Test
    void rejectsNonImagesForeignUploadsAndBadRequests() throws Exception {
        PresignedUpload fake = storage.presignUpload("10", "image/png", 20);
        put(fake, "<script>alert(1)</script>".substring(0, 20).getBytes());
        assertThatThrownBy(() -> storage.promote(fake.key(), "10", "chat/1"))
                .isInstanceOf(ApiException.class).hasMessageContaining("Only PNG");

        PresignedUpload mine = storage.presignUpload("11", "image/png", PNG.length);
        put(mine, PNG);
        assertThatThrownBy(() -> storage.promote(mine.key(), "12", "chat/1"))
                .as("someone else's upload").isInstanceOf(ApiException.class).hasMessage("Unknown upload");
        assertThatThrownBy(() -> storage.promote("tmp/11/../../avatars/x", "11", "chat/1")).isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> storage.presignUpload("11", "text/html", 10)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> storage.presignUpload("11", "image/png", ImageTypes.MAX_BYTES + 1)).isInstanceOf(ApiException.class);
    }
}
