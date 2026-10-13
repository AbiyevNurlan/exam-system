package com.examportal.exam.repository;

import com.examportal.common.domain.entity.MediaAsset;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.UUID;

/**
 * Media Asset Repository
 * Metadata rows of uploaded files. Every status change is one guarded statement, so two requests
 * can never both win (e.g. a confirmation and the cleanup job).
 * The build has no -parameters flag, so parameters that are reused in the SQL need explicit @Param names.
 */
@Repository
public interface MediaAssetRepository extends R2dbcRepository<MediaAsset, UUID> {

    /**
     * Create a PENDING asset unless the owner already has maxPending of them; empty when refused.
     * The caller serialises requests of one owner (advisory lock) so the count cannot be passed.
     */
    @Query("""
        INSERT INTO media_assets (owner_id, kind, storage_key, content_type, size_bytes, status, alt_text, created_at)
        SELECT :ownerId, :kind, :storageKey, :contentType, :sizeBytes, 'PENDING', :altText, LOCALTIMESTAMP
        WHERE (SELECT count(*) FROM media_assets WHERE owner_id = :ownerId AND status = 'PENDING') < :maxPending
        RETURNING *
    """)
    Mono<MediaAsset> insertPending(@Param("ownerId") UUID ownerId,
                                   @Param("kind") String kind,
                                   @Param("storageKey") String storageKey,
                                   @Param("contentType") String contentType,
                                   @Param("sizeBytes") long sizeBytes,
                                   @Param("altText") String altText,
                                   @Param("maxPending") int maxPending);

    /**
     * PENDING -> ACTIVE; returns 1 only for the request that made the change
     */
    @Modifying
    @Query("UPDATE media_assets SET status = 'ACTIVE', activated_at = LOCALTIMESTAMP WHERE id = :id AND status = 'PENDING'")
    Mono<Integer> activate(@Param("id") UUID id);

    /**
     * Soft delete; returns 1 only for the request that made the change
     */
    @Modifying
    @Query("UPDATE media_assets SET status = 'DELETED' WHERE id = :id AND status <> 'DELETED'")
    Mono<Integer> markDeleted(@Param("id") UUID id);

    /**
     * Soft delete of an asset that is still PENDING (rejected content, abandoned upload)
     */
    @Modifying
    @Query("UPDATE media_assets SET status = 'DELETED' WHERE id = :id AND status = 'PENDING'")
    Mono<Integer> markPendingDeleted(@Param("id") UUID id);

    /**
     * Uploads that were never confirmed, oldest first (database clock)
     */
    @Query("""
        SELECT * FROM media_assets
        WHERE status = 'PENDING' AND created_at < LOCALTIMESTAMP - make_interval(mins => :minutes)
        ORDER BY created_at
        LIMIT :limit
    """)
    Flux<MediaAsset> findStalePending(@Param("minutes") int minutes, @Param("limit") int limit);

    /**
     * ACTIVE assets that no question and no option of any exam (drafts included) uses and that
     * became usable more than `days` days ago, oldest first (database clock)
     */
    @Query("""
        SELECT m.* FROM media_assets m
        WHERE m.status = 'ACTIVE'
          AND COALESCE(m.activated_at, m.created_at) < LOCALTIMESTAMP - make_interval(days => :days)
          AND NOT EXISTS (SELECT 1 FROM question_media qm WHERE qm.media_id = m.id)
          AND NOT EXISTS (SELECT 1 FROM option_media om WHERE om.media_id = m.id)
        ORDER BY m.created_at
        LIMIT :limit
    """)
    Flux<MediaAsset> findOrphans(@Param("days") int days, @Param("limit") int limit);

    /**
     * Lock an ACTIVE asset until the end of the transaction; empty when it is not ACTIVE (any more).
     * FOR UPDATE waits for, and then blocks, every statement that attaches the asset
     * (MediaLinkRepository.attachToQuestion / attachToOption).
     */
    @Query("SELECT id FROM media_assets WHERE id = :id AND status = 'ACTIVE' FOR UPDATE")
    Mono<UUID> lockActive(@Param("id") UUID id);

    /**
     * ACTIVE -> DELETED, but only while nothing uses the asset. Call it after lockActive in the same
     * transaction: as its own statement it sees every link committed while the lock was waited for.
     */
    @Modifying
    @Query("""
        UPDATE media_assets m SET status = 'DELETED'
        WHERE m.id = :id AND m.status = 'ACTIVE'
          AND NOT EXISTS (SELECT 1 FROM question_media qm WHERE qm.media_id = m.id)
          AND NOT EXISTS (SELECT 1 FROM option_media om WHERE om.media_id = m.id)
    """)
    Mono<Integer> markOrphanDeleted(@Param("id") UUID id);

    @Query("SELECT * FROM media_assets WHERE id IN (:ids)")
    Flux<MediaAsset> findByIdIn(@Param("ids") Collection<UUID> ids);
}
