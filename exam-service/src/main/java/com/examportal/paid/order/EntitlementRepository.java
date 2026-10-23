package com.examportal.paid.order;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * entitlements (V24): the right of a guest to take an exam. Every write is one statement, part of the caller's
 * transaction; at most one ACTIVE per guest and exam (unique index), an attempt is taken by a conditional UPDATE.
 */
@Repository
@RequiredArgsConstructor
public class EntitlementRepository {

    public record Entitlement(Long id, UUID guestId, Long examId, Long orderId, String status, int attemptsLeft,
                              UUID grantedBy, String grantReason, LocalDateTime createdAt) {
    }

    private static final String COLUMNS = "id, guest_id, exam_id, order_id, status, attempts_left, granted_by, grant_reason, created_at";

    private final DatabaseClient databaseClient;

    public Mono<Boolean> hasActive(UUID guestId, Long examId) {
        return databaseClient.sql("SELECT EXISTS (SELECT 1 FROM entitlements WHERE guest_id = :guest AND exam_id = :exam AND status = 'ACTIVE')")
                .bind("guest", guestId)
                .bind("exam", examId)
                .map(row -> Boolean.TRUE.equals(row.get(0, Boolean.class)))
                .one();
    }

    /** The entitlement of a paid order; empty when the guest already has an ACTIVE one for the exam */
    public Mono<Entitlement> grantForOrder(Order order, int attempts) {
        return databaseClient.sql("INSERT INTO entitlements (guest_id, exam_id, order_id, status, attempts_left) "
                        + "VALUES (:guest, :exam, :order, 'ACTIVE', :attempts) "
                        + "ON CONFLICT (guest_id, exam_id) WHERE status = 'ACTIVE' DO NOTHING RETURNING " + COLUMNS)
                .bind("guest", order.guestId())
                .bind("exam", order.examId())
                .bind("order", order.id())
                .bind("attempts", attempts)
                .map(EntitlementRepository::entitlement)
                .one();
    }

    /** A free entitlement given by an administrator with a reason; empty when one is ACTIVE already */
    public Mono<Entitlement> grantByAdmin(UUID guestId, Long examId, int attempts, UUID adminId, String reason) {
        return databaseClient.sql("INSERT INTO entitlements (guest_id, exam_id, status, attempts_left, granted_by, grant_reason) "
                        + "VALUES (:guest, :exam, 'ACTIVE', :attempts, :admin, :reason) "
                        + "ON CONFLICT (guest_id, exam_id) WHERE status = 'ACTIVE' DO NOTHING RETURNING " + COLUMNS)
                .bind("guest", guestId)
                .bind("exam", examId)
                .bind("attempts", attempts)
                .bind("admin", adminId)
                .bind("reason", reason)
                .map(EntitlementRepository::entitlement)
                .one();
    }

    /** A refund ends what the order gave; how many rows changed */
    public Mono<Long> revokeForOrder(Long orderId) {
        return databaseClient.sql("UPDATE entitlements SET status = 'REVOKED', updated_at = LOCALTIMESTAMP WHERE order_id = :order AND status <> 'REVOKED'")
                .bind("order", orderId)
                .fetch().rowsUpdated();
    }

    /**
     * Takes one attempt of the guest's ACTIVE entitlement for the exam, in one statement: true when one was left. The last
     * attempt makes it USED. Two parallel starts: the row lock lets one through, the other finds none left.
     */
    public Mono<Boolean> takeAttempt(UUID guestId, Long examId) {
        return databaseClient.sql("UPDATE entitlements SET attempts_left = attempts_left - 1, "
                        + "status = CASE WHEN attempts_left - 1 = 0 THEN 'USED' ELSE status END, updated_at = LOCALTIMESTAMP "
                        + "WHERE guest_id = :guest AND exam_id = :exam AND status = 'ACTIVE' AND attempts_left > 0")
                .bind("guest", guestId)
                .bind("exam", examId)
                .fetch().rowsUpdated()
                .map(updated -> updated == 1);
    }

    public Flux<Entitlement> findOfGuest(UUID guestId) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM entitlements WHERE guest_id = :guest ORDER BY created_at DESC")
                .bind("guest", guestId)
                .map(EntitlementRepository::entitlement)
                .all();
    }

    static Entitlement entitlement(io.r2dbc.spi.Readable row) {
        return new Entitlement(row.get("id", Long.class), row.get("guest_id", UUID.class), row.get("exam_id", Long.class),
                row.get("order_id", Long.class), row.get("status", String.class), row.get("attempts_left", Integer.class),
                row.get("granted_by", UUID.class), row.get("grant_reason", String.class), row.get("created_at", LocalDateTime.class));
    }
}
