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

    @Query("SELECT * FROM media_assets WHERE id IN (:ids)")
    Flux<MediaAsset> findByIdIn(@Param("ids") Collection<UUID> ids);
}
