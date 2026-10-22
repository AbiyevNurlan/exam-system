package com.examportal.exam.service;

import com.examportal.common.domain.dto.profile.AvatarDtos;
import com.examportal.common.domain.entity.MediaKind;
import com.examportal.exam.exception.MediaException;
import com.examportal.exam.repository.AvatarRepository;
import com.examportal.exam.repository.AvatarRepository.Stored;
import com.examportal.exam.storage.MediaStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * The own profile picture of every signed-in user, in the media bucket like question media: the browser uploads
 * straight to the storage with a presigned URL, then asks for confirmation. Confirmation trusts nothing the browser
 * said: the stored file must have the declared size and its first bytes must be a JPEG, PNG or WebP image. A
 * confirmed picture replaces the one before; replaced and refused files are deleted from the storage.
 *
 * There is no user parameter anywhere: the picture is always the one of the token. URLs and keys are never logged.
 */
@Slf4j
@Service
public class AvatarService {

    private static final String KEY_PREFIX = "avatars/";

    private final AvatarRepository repository;
    private final MediaStorage storage;
    private final MediaTypePolicy typePolicy;
    private final TransactionalOperator transactionalOperator;
    private final long maxBytes;
    private final int uploadUrlTtlSeconds;
    private final int urlTtlSeconds;

    public AvatarService(AvatarRepository repository, MediaStorage storage, MediaTypePolicy typePolicy,
                         TransactionalOperator transactionalOperator,
                         @Value("${exam.media.avatar-max-bytes:2097152}") long maxBytes,
                         @Value("${exam.media.upload-url-ttl-seconds:600}") int uploadUrlTtlSeconds,
                         @Value("${exam.media.avatar-url-ttl-seconds:3600}") int urlTtlSeconds) {
        this.repository = repository;
        this.storage = storage;
        this.typePolicy = typePolicy;
        this.transactionalOperator = transactionalOperator;
        this.maxBytes = maxBytes;
        this.uploadUrlTtlSeconds = uploadUrlTtlSeconds;
        this.urlTtlSeconds = urlTtlSeconds;
    }

    /**
     * Register an upload and sign the URL for it. An earlier upload that was never confirmed is dropped.
     */
    public Mono<AvatarDtos.Upload> createUpload(UUID userId, AvatarDtos.UploadRequest request) {
        if (!storage.isConfigured()) {
            return Mono.error(MediaException.storageUnavailable(null));
        }
        if (!typePolicy.isAllowed(MediaKind.IMAGE, request.contentType())) {
            return Mono.error(MediaException.typeNotAllowed());
        }
        if (request.sizeBytes() > maxBytes) {
            return Mono.error(MediaException.tooLarge(maxBytes));
        }
        // The key is random: nothing of the user's file name reaches the storage
        String key = KEY_PREFIX + UUID.randomUUID();
        Mono<String> replaced = repository.lock(userId)
                .map(stored -> stored.pendingKey() == null ? "" : stored.pendingKey())
                .defaultIfEmpty("")
                .flatMap(previous -> repository.savePending(userId, key, request.contentType(), request.sizeBytes())
                        .thenReturn(previous));
        return transactionalOperator.transactional(replaced)
                .flatMap(previous -> previous.isEmpty() ? Mono.<Void>empty() : removeObject(previous))
                .then(Mono.fromSupplier(() -> new AvatarDtos.Upload(
                        storage.presignUpload(key, request.contentType(), request.sizeBytes(),
                                Duration.ofSeconds(uploadUrlTtlSeconds)),
                        "PUT", Map.of("Content-Type", request.contentType()), uploadUrlTtlSeconds)))
                .doOnSuccess(upload -> log.info("Avatar upload registered by user {}", userId));
    }

    /**
     * Check the uploaded file and make it the picture. Nothing to confirm: 404 MEDIA_NOT_FOUND. Not uploaded yet:
     * 409 MEDIA_NOT_UPLOADED (the upload stays). Wrong size or not an image: the file and the upload are removed,
     * 400 MEDIA_INVALID.
     */
    public Mono<AvatarDtos.Avatar> confirm(UUID userId) {
        Mono<Outcome> check = repository.lock(userId)
                .filter(stored -> stored.pendingKey() != null)
                .switchIfEmpty(Mono.error(MediaException.notFound()))
                .flatMap(stored -> storage.findSize(stored.pendingKey())
                        .switchIfEmpty(Mono.error(MediaException.notUploaded()))
                        .flatMap(size -> size.equals(stored.pendingSizeBytes()) && size <= maxBytes
                                ? storage.readFirstBytes(stored.pendingKey(), MediaTypePolicy.SIGNATURE_BYTES)
                                        .map(head -> typePolicy.matchesContent(stored.pendingContentType(), head))
                                : Mono.just(false))
                        .flatMap(valid -> valid
                                ? repository.activatePending(userId)
                                        .thenReturn(new Outcome(true, stored.pendingKey(), stored.storageKey()))
                                : repository.clearPending(userId)
                                        .thenReturn(new Outcome(false, stored.pendingKey(), stored.pendingKey()))));
        return transactionalOperator.transactional(check)
                .flatMap(outcome -> (outcome.removeKey() == null ? Mono.<Void>empty() : removeObject(outcome.removeKey()))
                        .then(Mono.defer(() -> {
                            if (!outcome.valid()) {
                                log.warn("Avatar of user {} rejected: the stored file is not the declared image or size", userId);
                                return Mono.<AvatarDtos.Avatar>error(MediaException.invalid());
                            }
                            log.info("Avatar of user {} confirmed", userId);
                            return Mono.just(toAvatar(outcome.key()));
                        })));
    }

    /**
     * The picture as a short-lived URL; url = null when there is none (or the storage is not configured)
     */
    public Mono<AvatarDtos.Avatar> get(UUID userId) {
        if (!storage.isConfigured()) {
            return Mono.just(AvatarDtos.Avatar.none());
        }
        return repository.find(userId)
                .filter(stored -> stored.storageKey() != null)
                .map(stored -> toAvatar(stored.storageKey()))
                .defaultIfEmpty(AvatarDtos.Avatar.none());
    }

    /**
     * Remove the picture and an unconfirmed upload; removing what does not exist is not an error
     */
    public Mono<Void> delete(UUID userId) {
        Mono<Stored> removal = repository.lock(userId)
                .flatMap(stored -> repository.delete(userId).thenReturn(stored));
        // The row goes first; a failed file removal leaves an unreferenced file, never a picture that cannot load
        return transactionalOperator.transactional(removal)
                .flatMap(stored -> (stored.storageKey() == null ? Mono.<Void>empty() : removeObject(stored.storageKey()))
                        .then(stored.pendingKey() == null ? Mono.<Void>empty() : removeObject(stored.pendingKey())))
                .doOnSuccess(v -> log.info("Avatar of user {} removed", userId));
    }

    // ==================== Private Helper Methods ====================

    private record Outcome(boolean valid, String key, String removeKey) {
    }

    private AvatarDtos.Avatar toAvatar(String key) {
        return new AvatarDtos.Avatar(storage.presignDownload(key, Duration.ofSeconds(urlTtlSeconds)), urlTtlSeconds);
    }

    /** A failure is logged (type only) and does not undo the change of the row */
    private Mono<Void> removeObject(String key) {
        return storage.delete(key)
                .onErrorResume(e -> {
                    log.error("Avatar file could not be removed: {}", e.getClass().getSimpleName());
                    return Mono.empty();
                });
    }
}
