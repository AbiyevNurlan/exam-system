package com.examportal.exam.service;

import com.examportal.common.domain.dto.media.MediaResponse;
import com.examportal.common.domain.dto.media.MediaUploadRequest;
import com.examportal.common.domain.dto.media.MediaUploadResponse;
import com.examportal.common.domain.dto.media.MediaUrlResponse;
import com.examportal.common.domain.entity.MediaAsset;
import com.examportal.common.domain.entity.MediaStatus;
import com.examportal.exam.exception.MediaException;
import com.examportal.exam.repository.MediaAssetRepository;
import com.examportal.exam.repository.MediaLinkRepository;
import com.examportal.exam.storage.MediaStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Media Service
 * Uploads of question media by ADMIN and INSTRUCTOR (the controller enforces the role).
 *
 * The file never passes through this service: the client gets a presigned URL, uploads straight to
 * the storage and then asks for confirmation. Only confirmation makes an asset usable (ACTIVE), and
 * it trusts nothing the client said: the stored object must have the declared size and its first
 * bytes must be the declared type. Anything else deletes the object and the asset.
 *
 * Someone else's asset is a 404, like an unknown one. URLs and storage keys are never logged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaService {

    private static final int CLEANUP_BATCH = 200;

    private final MediaAssetRepository assetRepository;
    private final MediaLinkRepository linkRepository;
    private final MediaStorage storage;
    private final MediaTypePolicy typePolicy;
    private final DatabaseClient databaseClient;
    private final TransactionalOperator transactionalOperator;

    @Value("${exam.media.upload-url-ttl-seconds:600}")
    private int uploadUrlTtlSeconds;

    @Value("${exam.media.preview-url-ttl-seconds:300}")
    private int previewUrlTtlSeconds;

    @Value("${exam.media.max-pending-per-user:50}")
    private int maxPendingPerUser;

    @Value("${exam.media.pending-max-age-minutes:60}")
    private int pendingMaxAgeMinutes;

    // Not positive = the orphan cleanup is switched off
    @Value("${exam.media.orphan-retention-days:14}")
    private int orphanRetentionDays;

    /**
     * Register an upload and sign the URL for it
     */
    public Mono<MediaUploadResponse> createUpload(MediaUploadRequest request, UUID userId) {
        if (!storage.isConfigured()) {
            return Mono.error(MediaException.storageUnavailable(null));
        }
        if (!typePolicy.isAllowed(request.getKind(), request.getContentType())) {
            return Mono.error(MediaException.typeNotAllowed());
        }
        if (request.getSizeBytes() > typePolicy.maxBytes(request.getKind())) {
            return Mono.error(MediaException.tooLarge(typePolicy.maxBytes(request.getKind())));
        }

        // The key is random: nothing of the user's file name ever reaches the storage
        String storageKey = UUID.randomUUID().toString();
        String altText = request.getAltText() == null || request.getAltText().isBlank() ? null : request.getAltText().trim();

        // The advisory lock makes uploads of one user take turns, so the PENDING limit cannot be passed in parallel.
        // @Transactional is not applied at runtime in this service: the TransactionalOperator is the boundary.
        Mono<MediaAsset> creation = databaseClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(:owner, 0))")
                .bind("owner", "media-upload:" + userId)
                .then()
                .then(assetRepository.insertPending(userId, request.getKind().name(), storageKey,
                        request.getContentType(), request.getSizeBytes(), altText, maxPendingPerUser))
                .switchIfEmpty(Mono.error(MediaException.pendingLimit(maxPendingPerUser)));

        return transactionalOperator.transactional(creation)
                .map(asset -> MediaUploadResponse.builder()
                        .mediaId(asset.getId())
                        .uploadUrl(storage.presignUpload(storageKey, asset.getContentType(), asset.getSizeBytes(),
                                Duration.ofSeconds(uploadUrlTtlSeconds)))
                        .method("PUT")
                        .headers(Map.of("Content-Type", asset.getContentType()))
                        .expiresIn(uploadUrlTtlSeconds)
                        .build())
                .doOnSuccess(response -> log.info("Upload registered: media {} by user {}", response.getMediaId(), userId));
    }

    /**
     * Check the uploaded object and activate the asset.
     * Not uploaded yet: 409, the asset stays PENDING. Wrong size or wrong content: the object and
     * the asset are deleted, 400 MEDIA_INVALID. Confirming an ACTIVE asset again changes nothing.
     */
    public Mono<MediaResponse> confirm(UUID mediaId, UUID userId, String role) {
        return findOwned(mediaId, userId, role)
                .flatMap(asset -> {
                    if (asset.getStatus() == MediaStatus.ACTIVE) {
                        return Mono.just(asset);
                    }
                    return storage.findSize(asset.getStorageKey())
                            .switchIfEmpty(Mono.error(MediaException.notUploaded()))
                            .flatMap(size -> size.equals(asset.getSizeBytes())
                                    && size <= typePolicy.maxBytes(asset.getKind())
                                    ? storage.readFirstBytes(asset.getStorageKey(), MediaTypePolicy.SIGNATURE_BYTES)
                                            .map(head -> typePolicy.matchesContent(asset.getContentType(), head))
                                    : Mono.just(false))
                            .flatMap(valid -> valid ? activate(asset) : reject(asset));
                })
                .map(this::toResponse);
    }

    /**
     * Short-lived URL for the owner (or ADMIN) to look at an ACTIVE asset
     */
    public Mono<MediaUrlResponse> previewUrl(UUID mediaId, UUID userId, String role) {
        return findOwned(mediaId, userId, role)
                .filter(asset -> asset.getStatus() == MediaStatus.ACTIVE)
                .switchIfEmpty(Mono.error(MediaException.notFound()))
                .map(asset -> MediaUrlResponse.builder()
                        .url(storage.presignDownload(asset.getStorageKey(), Duration.ofSeconds(previewUrlTtlSeconds)))
                        .kind(asset.getKind())
                        .expiresIn(previewUrlTtlSeconds)
                        .build());
    }

    /**
     * Soft delete plus removal of the object. Refused while a published exam uses the media;
     * in drafts the media simply disappears from its questions and options.
     */
    public Mono<Void> delete(UUID mediaId, UUID userId, String role) {
        Mono<MediaAsset> removal = findOwned(mediaId, userId, role)
                .flatMap(asset -> linkRepository.isAttachedToPublishedExam(mediaId)
                        .flatMap(inUse -> inUse
                                ? Mono.<MediaAsset>error(MediaException.inUse())
                                : linkRepository.detachEverywhere(mediaId)
                                        .then(assetRepository.markDeleted(mediaId))
                                        .thenReturn(asset)));

        // The row is committed as DELETED first; a failed object removal leaves an unreferenced object, never a usable asset
        return transactionalOperator.transactional(removal)
                .flatMap(asset -> removeObject(asset))
                .doOnSuccess(v -> log.info("Media {} deleted by user {}", mediaId, userId));
    }

    /**
     * Uploads that were never confirmed: asset marked DELETED, object removed.
     * Emits how many assets were cleaned up.
     */
    public Mono<Long> cleanupStalePending() {
        if (!storage.isConfigured()) {
            return Mono.just(0L);
        }
        return assetRepository.findStalePending(pendingMaxAgeMinutes, CLEANUP_BATCH)
                // The guarded update decides: an asset that was confirmed in the meantime is left alone
                .concatMap(asset -> assetRepository.markPendingDeleted(asset.getId())
                        .filter(updated -> updated > 0)
                        .flatMap(updated -> removeObject(asset).thenReturn(asset)))
                .count();
    }

    /**
     * Confirmed files that nothing uses: ACTIVE, attached to no question and no option of any exam
     * (drafts included), and older than exam.media.orphan-retention-days. The retention is long on
     * purpose: an admin may upload into a draft kept in the browser and come back days later.
     * Emits how many assets were removed; switched off by a retention that is not positive.
     *
     * Per asset, in one transaction: lock the row, mark it DELETED only if it is still unused, then
     * delete the object. A media in use is never touched, and a storage failure rolls the status
     * back, so the asset stays ACTIVE and is tried again on the next run.
     */
    public Mono<Long> cleanupOrphans() {
        if (orphanRetentionDays <= 0 || !storage.isConfigured()) {
            return Mono.just(0L);
        }
        return assetRepository.findOrphans(orphanRetentionDays, CLEANUP_BATCH)
                .concatMap(asset -> transactionalOperator.transactional(
                                assetRepository.lockActive(asset.getId())
                                        .flatMap(assetRepository::markOrphanDeleted)
                                        .filter(updated -> updated > 0)
                                        .flatMap(updated -> storage.delete(asset.getStorageKey()).thenReturn(asset)))
                        .onErrorResume(e -> {
                            log.warn("Orphan media {} not removed, will be retried: {}", asset.getId(), e.getClass().getSimpleName());
                            return Mono.empty();
                        }))
                .count();
    }

    // ==================== Private Helper Methods ====================

    /**
     * The asset if it exists, is not deleted and belongs to the user (ADMIN: any); otherwise 404
     */
    private Mono<MediaAsset> findOwned(UUID mediaId, UUID userId, String role) {
        return assetRepository.findById(mediaId)
                .filter(asset -> asset.getStatus() != MediaStatus.DELETED)
                .filter(asset -> "ADMIN".equals(role) || asset.getOwnerId().equals(userId))
                .switchIfEmpty(Mono.error(MediaException.notFound()));
    }

    private Mono<MediaAsset> activate(MediaAsset asset) {
        return assetRepository.activate(asset.getId())
                .flatMap(updated -> assetRepository.findById(asset.getId()))
                // Deleted in the meantime (cleanup job, parallel delete): it is gone for the caller too
                .filter(current -> current.getStatus() == MediaStatus.ACTIVE)
                .switchIfEmpty(Mono.error(MediaException.notFound()))
                .doOnNext(current -> log.info("Media {} confirmed ({})", current.getId(), current.getKind()));
    }

    /**
     * The stored file is not what was declared: the asset can never become ACTIVE
     */
    private Mono<MediaAsset> reject(MediaAsset asset) {
        return assetRepository.markPendingDeleted(asset.getId())
                .then(removeObject(asset))
                .then(Mono.defer(() -> {
                    log.warn("Media {} rejected: stored content does not match the declared type or size", asset.getId());
                    return Mono.<MediaAsset>error(MediaException.invalid());
                }));
    }

    /**
     * Remove the object; a failure is logged (type only) and does not undo the soft delete
     */
    private Mono<Void> removeObject(MediaAsset asset) {
        return storage.delete(asset.getStorageKey())
                .onErrorResume(e -> {
                    log.error("Object of media {} could not be removed: {}", asset.getId(), e.getClass().getSimpleName());
                    return Mono.empty();
                });
    }

    private MediaResponse toResponse(MediaAsset asset) {
        return MediaResponse.builder()
                .id(asset.getId())
                .kind(asset.getKind())
                .contentType(asset.getContentType())
                .sizeBytes(asset.getSizeBytes())
                .status(asset.getStatus())
                .altText(asset.getAltText())
                .createdAt(asset.getCreatedAt())
                .activatedAt(asset.getActivatedAt())
                .build();
    }
}