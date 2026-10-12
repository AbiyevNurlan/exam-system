package com.examportal.exam.storage;

import com.examportal.exam.exception.MediaException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * Media Storage
 * The only class that talks to the S3-compatible object storage (SeaweedFS in development).
 *
 * - The services never carry file content: browsers upload and download directly with presigned
 *   URLs. This class signs those URLs and reads object metadata and the first bytes for checks.
 * - Two endpoints: `endpoint` is how this service reaches the storage; `public-endpoint` is the
 *   host written into presigned URLs and must be reachable from the browser. The signature covers
 *   the host, so a URL signed for one endpoint does not work on the other.
 * - The bucket is private. It is created at startup together with its CORS rules; no policy ever
 *   allows anonymous access.
 * - Without credentials the service still starts; every operation then fails with 503
 *   MEDIA_STORAGE_UNAVAILABLE.
 * - Presigned URLs and storage keys are never logged.
 */
@Slf4j
@Component
public class MediaStorage {

    @Value("${exam.media.storage.endpoint:}")
    private String endpoint;

    @Value("${exam.media.storage.public-endpoint:}")
    private String publicEndpoint;

    @Value("${exam.media.storage.region:us-east-1}")
    private String region;

    @Value("${exam.media.storage.bucket:exam-media}")
    private String bucket;

    @Value("${exam.media.storage.access-key:}")
    private String accessKey;

    @Value("${exam.media.storage.secret-key:}")
    private String secretKey;

    @Value("${exam.media.storage.cors-allowed-origins:}")
    private String corsAllowedOrigins;

    private S3AsyncClient client;
    private S3Presigner presigner;

    @PostConstruct
    void init() {
        if (isBlank(endpoint) || isBlank(accessKey) || isBlank(secretKey)) {
            log.warn("Media storage is not configured (MEDIA_S3_ENDPOINT, MEDIA_S3_ACCESS_KEY, MEDIA_S3_SECRET_KEY): "
                    + "media endpoints answer 503");
            return;
        }
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey.trim(), secretKey.trim()));
        // Path style (http://host/bucket/key): the storage has no per-bucket host names
        S3Configuration pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();

        client = S3AsyncClient.builder()
                .endpointOverride(URI.create(endpoint.trim()))
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();
        presigner = S3Presigner.builder()
                .endpointOverride(URI.create(isBlank(publicEndpoint) ? endpoint.trim() : publicEndpoint.trim()))
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();
    }

    @PreDestroy
    void close() {
        if (client != null) {
            client.close();
            presigner.close();
        }
    }

    public boolean isConfigured() {
        return client != null;
    }

    /**
     * Create the private bucket if it is missing and (re)write its CORS rules. The storage may come
     * up after this service, so it is retried; a failure is logged and never stops the service.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void prepareBucket() {
        if (!isConfigured()) {
            return;
        }
        ensureBucket()
                .then(Mono.defer(this::applyCors))
                .retryWhen(Retry.fixedDelay(20, Duration.ofSeconds(3)))
                .subscribe(
                        unused -> { },
                        error -> log.error("Media bucket could not be prepared: {}", error.getClass().getSimpleName()),
                        () -> log.info("Media bucket '{}' is ready", bucket));
    }

    /**
     * URL for one direct upload (HTTP PUT). Content type and length are part of the signature: the
     * upload must send exactly these, anything else is refused by the storage.
     */
    public String presignUpload(String key, String contentType, long sizeBytes, Duration ttl) {
        requireConfigured();
        return presigner.presignPutObject(request -> request
                        .signatureDuration(ttl)
                        .putObjectRequest(put -> put.bucket(bucket).key(key)
                                .contentType(contentType)
                                .contentLength(sizeBytes)))
                .url().toString();
    }

    /**
     * URL for one direct download (HTTP GET), valid for the given time
     */
    public String presignDownload(String key, Duration ttl) {
        requireConfigured();
        return presigner.presignGetObject(request -> request
                        .signatureDuration(ttl)
                        .getObjectRequest(get -> get.bucket(bucket).key(key)))
                .url().toString();
    }

    /**
     * Size of the stored object in bytes; empty when there is no such object
     */
    public Mono<Long> findSize(String key) {
        return call(Mono.defer(() -> Mono.fromFuture(client.headObject(head -> head.bucket(bucket).key(key)))))
                .map(head -> head.contentLength())
                .onErrorResume(this::isNotFound, e -> Mono.empty());
    }

    /**
     * The first bytes of the object (fewer when it is shorter)
     */
    public Mono<byte[]> readFirstBytes(String key, int count) {
        return call(Mono.defer(() -> Mono.fromFuture(client.getObject(
                        get -> get.bucket(bucket).key(key).range("bytes=0-" + (count - 1)),
                        AsyncResponseTransformer.toBytes()))))
                .map(response -> {
                    byte[] bytes = response.asByteArray();
                    // A storage that ignores the range must not make this read a whole video into memory
                    return bytes.length > count ? Arrays.copyOf(bytes, count) : bytes;
                });
    }

    /**
     * Delete the object; deleting one that does not exist is not an error
     */
    public Mono<Void> delete(String key) {
        return call(Mono.defer(() -> Mono.fromFuture(client.deleteObject(delete -> delete.bucket(bucket).key(key)))))
                .onErrorResume(this::isNotFound, e -> Mono.empty())
                .then();
    }

    // ==================== Private Helper Methods ====================

    private Mono<Void> ensureBucket() {
        return Mono.defer(() -> Mono.fromFuture(client.headBucket(head -> head.bucket(bucket))))
                .then()
                .onErrorResume(this::isNotFound, e -> Mono.fromFuture(client.createBucket(create -> create.bucket(bucket)))
                        .doOnSuccess(created -> log.info("Media bucket '{}' created (private)", bucket))
                        .then());
    }

    /**
     * Browsers upload and download straight to the storage, so the storage itself must allow the
     * frontend origins (MEDIA_CORS_ORIGINS). No origins = no CORS rules = no browser access.
     */
    private Mono<Void> applyCors() {
        List<String> origins = Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        if (origins.isEmpty()) {
            log.warn("MEDIA_CORS_ORIGINS is empty: browsers cannot upload or load media");
            return Mono.empty();
        }
        CORSRule rule = CORSRule.builder()
                .allowedOrigins(origins)
                .allowedMethods("GET", "HEAD", "PUT")
                .allowedHeaders("*")
                .exposeHeaders("ETag")
                .maxAgeSeconds(600)
                .build();
        return Mono.fromFuture(client.putBucketCors(cors -> cors.bucket(bucket)
                        .corsConfiguration(configuration -> configuration.corsRules(rule))))
                .doOnSuccess(done -> log.info("Media bucket CORS set for {} origin(s)", origins.size()))
                .then();
    }

    private <T> Mono<T> call(Mono<T> operation) {
        if (!isConfigured()) {
            return Mono.error(MediaException.storageUnavailable(null));
        }
        // Only the exception type is kept: storage messages can contain keys and request details
        return operation.onErrorMap(e -> !isNotFound(e) && !(e instanceof MediaException),
                e -> MediaException.storageUnavailable(new IllegalStateException(unwrap(e).getClass().getSimpleName())));
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw MediaException.storageUnavailable(null);
        }
    }

    private boolean isNotFound(Throwable error) {
        Throwable cause = unwrap(error);
        return cause instanceof NoSuchKeyException
                || cause instanceof NoSuchBucketException
                || (cause instanceof S3Exception s3 && s3.statusCode() == 404);
    }

    private Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
