package com.examportal.exam.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * The profile pictures (user_avatars, exam V20): one row per account, the confirmed picture and at most one upload
 * waiting for its confirmation. The writes are meant to run in one transaction after lock().
 */
@Repository
@RequiredArgsConstructor
public class AvatarRepository {

    private final DatabaseClient databaseClient;

    /** A row; every field may be null */
    public record Stored(String storageKey, String contentType, String pendingKey, String pendingContentType,
                         Long pendingSizeBytes) {
    }

    public Mono<Stored> find(UUID userId) {
        return select(userId, "");
    }

    /** The row, locked until the end of the transaction (empty when the account has none) */
    public Mono<Stored> lock(UUID userId) {
        return select(userId, " FOR UPDATE");
    }

    /** Store a new unconfirmed upload; it replaces the one before (whose file the caller deletes) */
    public Mono<Void> savePending(UUID userId, String key, String contentType, long sizeBytes) {
        return databaseClient.sql("""
                        INSERT INTO user_avatars (user_id, pending_key, pending_content_type, pending_size_bytes,
                                                  pending_created_at, updated_at)
                        VALUES (:user, :key, :type, :size, now() AT TIME ZONE 'UTC', now() AT TIME ZONE 'UTC')
                        ON CONFLICT (user_id) DO UPDATE SET pending_key = EXCLUDED.pending_key,
                            pending_content_type = EXCLUDED.pending_content_type,
                            pending_size_bytes = EXCLUDED.pending_size_bytes,
                            pending_created_at = EXCLUDED.pending_created_at, updated_at = EXCLUDED.updated_at
                        """)
                .bind("user", userId).bind("key", key).bind("type", contentType).bind("size", sizeBytes)
                .then();
    }

    /** The confirmed upload becomes the picture */
    public Mono<Void> activatePending(UUID userId) {
        return databaseClient.sql("""
                        UPDATE user_avatars SET storage_key = pending_key, content_type = pending_content_type,
                            size_bytes = pending_size_bytes, pending_key = NULL, pending_content_type = NULL,
                            pending_size_bytes = NULL, pending_created_at = NULL, updated_at = now() AT TIME ZONE 'UTC'
                        WHERE user_id = :user AND pending_key IS NOT NULL
                        """)
                .bind("user", userId)
                .then();
    }

    public Mono<Void> clearPending(UUID userId) {
        return databaseClient.sql("""
                        UPDATE user_avatars SET pending_key = NULL, pending_content_type = NULL, pending_size_bytes = NULL,
                            pending_created_at = NULL, updated_at = now() AT TIME ZONE 'UTC'
                        WHERE user_id = :user
                        """)
                .bind("user", userId)
                .then();
    }

    public Mono<Void> delete(UUID userId) {
        return databaseClient.sql("DELETE FROM user_avatars WHERE user_id = :user").bind("user", userId).then();
    }

    private Mono<Stored> select(UUID userId, String lock) {
        return databaseClient.sql("""
                        SELECT storage_key, content_type, pending_key, pending_content_type, pending_size_bytes
                        FROM user_avatars WHERE user_id = :user""" + lock)
                .bind("user", userId)
                .map(row -> new Stored(row.get("storage_key", String.class), row.get("content_type", String.class),
                        row.get("pending_key", String.class), row.get("pending_content_type", String.class),
                        row.get("pending_size_bytes", Long.class)))
                .one();
    }
}
