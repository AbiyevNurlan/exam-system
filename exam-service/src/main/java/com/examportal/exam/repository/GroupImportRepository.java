package com.examportal.exam.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.UUID;

/**
 * Group Import Repository
 * Set-based statements for the CSV import and for pending invites: one statement per batch instead
 * of one per row. Arrays are bound as a single parameter. All writes are idempotent (ON CONFLICT DO NOTHING).
 */
@Repository
@RequiredArgsConstructor
public class GroupImportRepository {

    private final DatabaseClient databaseClient;

    public Flux<UUID> findMemberIds(Long groupId, Collection<UUID> userIds) {
        return databaseClient.sql("""
                        SELECT user_id FROM user_group_members
                        WHERE group_id = :groupId AND user_id = ANY(CAST(:userIds AS UUID[]))
                        """)
                .bind("groupId", groupId)
                .bind("userIds", userIds.toArray(new UUID[0]))
                .map(row -> row.get("user_id", UUID.class))
                .all();
    }

    public Flux<String> findInvitedEmails(Long groupId, Collection<String> emails) {
        return databaseClient.sql("""
                        SELECT email_normalized FROM pending_group_invites
                        WHERE group_id = :groupId AND email_normalized = ANY(CAST(:emails AS VARCHAR[]))
                        """)
                .bind("groupId", groupId)
                .bind("emails", emails.toArray(new String[0]))
                .map(row -> row.get("email_normalized", String.class))
                .all();
    }

    /**
     * Add the users as members with source CSV; returns how many were new
     */
    public Mono<Long> addMembers(Long groupId, Collection<UUID> userIds, UUID addedBy) {
        return databaseClient.sql("""
                        INSERT INTO user_group_members (group_id, user_id, source, added_by, created_at)
                        SELECT :groupId, u, 'CSV', :addedBy, CURRENT_TIMESTAMP
                        FROM unnest(CAST(:userIds AS UUID[])) AS u
                        ON CONFLICT (group_id, user_id) DO NOTHING
                        """)
                .bind("groupId", groupId)
                .bind("addedBy", addedBy)
                .bind("userIds", userIds.toArray(new UUID[0]))
                .fetch()
                .rowsUpdated();
    }

    /**
     * Store the emails as pending invites; returns how many were new
     */
    public Mono<Long> addInvites(Long groupId, Collection<String> emails, UUID createdBy) {
        return databaseClient.sql("""
                        INSERT INTO pending_group_invites (group_id, email_normalized, created_by, created_at)
                        SELECT :groupId, e, :createdBy, CURRENT_TIMESTAMP
                        FROM unnest(CAST(:emails AS VARCHAR[])) AS e
                        ON CONFLICT (group_id, email_normalized) DO NOTHING
                        """)
                .bind("groupId", groupId)
                .bind("createdBy", createdBy)
                .bind("emails", emails.toArray(new String[0]))
                .fetch()
                .rowsUpdated();
    }

    public Mono<Long> removeInvites(Long groupId, Collection<String> emails) {
        return databaseClient.sql("""
                        DELETE FROM pending_group_invites
                        WHERE group_id = :groupId AND email_normalized = ANY(CAST(:emails AS VARCHAR[]))
                        """)
                .bind("groupId", groupId)
                .bind("emails", emails.toArray(new String[0]))
                .fetch()
                .rowsUpdated();
    }

    /**
     * Turn every pending invite of this email into a membership of the user, in ONE atomic statement:
     * the invites are consumed, the memberships (source CSV, added by whoever imported the email) are
     * created for groups that still exist, and each new membership gets its audit row.
     * Returns the number of groups joined; 0 when there was nothing to apply (safe to repeat).
     */
    public Mono<Long> applyPendingInvites(UUID userId, String email) {
        return databaseClient.sql("""
                        WITH taken AS (
                            DELETE FROM pending_group_invites
                            WHERE email_normalized = :email
                            RETURNING group_id, created_by
                        ), added AS (
                            INSERT INTO user_group_members (group_id, user_id, source, added_by, created_at)
                            SELECT t.group_id, :userId, 'CSV', t.created_by, CURRENT_TIMESTAMP
                            FROM taken t
                            JOIN user_groups g ON g.id = t.group_id AND g.active = true
                            ON CONFLICT (group_id, user_id) DO NOTHING
                            RETURNING group_id, added_by
                        )
                        INSERT INTO group_audit_log (actor_id, action, group_id, target_user_id, details, created_at)
                        SELECT a.added_by, 'INVITE_APPLIED', a.group_id, :userId,
                               'pending invite applied for a verified account', CURRENT_TIMESTAMP
                        FROM added a
                        """)
                .bind("email", email)
                .bind("userId", userId)
                .fetch()
                .rowsUpdated();
    }
}