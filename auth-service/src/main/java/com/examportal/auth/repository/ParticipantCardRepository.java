package com.examportal.auth.repository;

import com.examportal.common.domain.dto.internal.AdminAuditQuery;
import com.examportal.common.domain.dto.participant.ParticipantCardDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Participant Card Repository (B11)
 * The account part of the admin's participant card: a fixed number of statements, whatever the size of the
 * tables. The account by its primary key with only the columns the card shows (no password hash, no
 * counters of failed logins), and the newest account events of the user (idx_admin_audit_log_target_user_id,
 * idx_admin_audit_log_actor_created).
 */
@Repository
@RequiredArgsConstructor
public class ParticipantCardRepository {

    private static final String LOCKED_NOW =
            "(account_locked = true AND (locked_until IS NULL OR locked_until > LOCALTIMESTAMP))";

    private final DatabaseClient databaseClient;

    /**
     * The account, active or not; empty when there is no such row
     */
    public Mono<ParticipantCardDtos.AuthCard> findAccount(UUID userId) {
        return databaseClient.sql("SELECT id, participant_code, first_name, last_name, email, phone_number, role, created_at, "
                        + "COALESCE(email_verified, false) AS email_verified, COALESCE(active, true) AS active, "
                        + LOCKED_NOW + " AS blocked, CASE WHEN " + LOCKED_NOW + " THEN locked_until END AS locked_until "
                        + "FROM users WHERE id = :userId")
                .bind("userId", userId)
                .map(row -> ParticipantCardDtos.AuthCard.builder()
                        .id(row.get("id", UUID.class))
                        .participantCode(row.get("participant_code", Integer.class))
                        .firstName(row.get("first_name", String.class))
                        .lastName(row.get("last_name", String.class))
                        .email(row.get("email", String.class))
                        .phoneNumber(row.get("phone_number", String.class))
                        .role(row.get("role", String.class))
                        .registeredAt(row.get("created_at", LocalDateTime.class))
                        .emailVerified(Boolean.TRUE.equals(row.get("email_verified", Boolean.class)))
                        .active(Boolean.TRUE.equals(row.get("active", Boolean.class)))
                        .blocked(Boolean.TRUE.equals(row.get("blocked", Boolean.class)))
                        .lockedUntil(row.get("locked_until", LocalDateTime.class))
                        .build())
                .one();
    }

    /**
     * The newest admin_audit_log rows where the user is the actor or the target, at most limit
     */
    public Mono<List<AdminAuditQuery.Entry>> findAccountEvents(UUID userId, int limit) {
        return databaseClient.sql("""
                        SELECT id, admin_user_id, action, target_user_id, details, created_at FROM admin_audit_log
                        WHERE admin_user_id = :userId OR target_user_id = :userId
                        ORDER BY created_at DESC NULLS LAST, id DESC
                        LIMIT :limit
                        """)
                .bind("userId", userId)
                .bind("limit", limit)
                .map(row -> AdminAuditQuery.Entry.builder()
                        .id(row.get("id", UUID.class))
                        .actorId(row.get("admin_user_id", UUID.class))
                        .action(row.get("action", String.class))
                        .targetUserId(row.get("target_user_id", UUID.class))
                        .details(row.get("details", String.class))
                        .createdAt(row.get("created_at", LocalDateTime.class))
                        .build())
                .all()
                .collectList();
    }
}
