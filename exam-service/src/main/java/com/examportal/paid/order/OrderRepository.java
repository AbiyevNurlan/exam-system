package com.examportal.paid.order;

import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * orders (V24): every write is one statement, part of the caller's transaction. A status changes only through
 * move(), which the trigger of V24 checks again; a seat is given back once (seat_held true -> false in the same
 * statement as the count of the exam).
 */
@Repository
@RequiredArgsConstructor
public class OrderRepository {

    static final String COLUMNS = "o.id, o.order_no, o.guest_id, o.guest_code, o.exam_id, o.amount_minor, o.currency, o.status, "
            + "o.provider, o.provider_ref, o.seat_held, o.review_reason, o.refund_reason, o.created_at, o.updated_at, o.expires_at, "
            + "o.paid_at, o.refund_requested_at, o.refunded_at";

    private final DatabaseClient databaseClient;

    /** A transaction-level lock for one guest and one exam: two orders of the same guest take turns */
    public Mono<Void> lockGuestExam(UUID guestId, Long examId) {
        return databaseClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .bind("key", "paid-order:" + guestId + ":" + examId)
                .map(row -> Boolean.TRUE)
                .one()
                .then();
    }

    /**
     * Every PENDING order whose time is over becomes EXPIRED and gives its seat back, in one statement (orders locked by
     * someone else are left for the next run). examId null: all exams. The expired orders.
     */
    public Flux<Order> expireStale(Long examId) {
        String scope = examId == null ? "" : " AND exam_id = :examId";
        DatabaseClient.GenericExecuteSpec sql = databaseClient.sql("""
                WITH stale AS (
                    SELECT id, exam_id, seat_held FROM orders
                    WHERE status = 'PENDING' AND expires_at <= LOCALTIMESTAMP""" + scope + """
                    ORDER BY id
                    FOR UPDATE SKIP LOCKED
                ), expired AS (
                    UPDATE orders o SET status = 'EXPIRED', seat_held = false
                    FROM stale WHERE o.id = stale.id
                    RETURNING o.*, stale.seat_held AS held
                ), released AS (
                    UPDATE exams e SET seats_taken = GREATEST(0, e.seats_taken - c.n)
                    FROM (SELECT exam_id, count(*) AS n FROM expired WHERE held GROUP BY exam_id) c
                    WHERE e.id = c.exam_id
                )
                """ + " SELECT " + COLUMNS.replace("o.", "x.") + " FROM expired x");
        if (examId != null) {
            sql = sql.bind("examId", examId);
        }
        return sql.map(OrderRepository::order).all();
    }

    public Mono<Order> findPending(UUID guestId, Long examId) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM orders o WHERE o.guest_id = :guest AND o.exam_id = :exam AND o.status = 'PENDING'")
                .bind("guest", guestId)
                .bind("exam", examId)
                .map(OrderRepository::order)
                .one();
    }

    /** A new PENDING order holding a seat; the order number is YK-yymmdd-nnnnnn (unique by its sequence) */
    public Mono<Order> insertPending(UUID guestId, int guestCode, Long examId, int amountMinor, String currency, String provider,
                                     int ttlMinutes) {
        return databaseClient.sql("INSERT INTO orders (order_no, guest_id, guest_code, exam_id, amount_minor, currency, status, provider, "
                        + "seat_held, expires_at) VALUES ('YK-' || to_char(LOCALTIMESTAMP, 'YYMMDD') || '-' || lpad(nextval('order_no_seq')::text, 6, '0'), "
                        + ":guest, :code, :exam, :amount, :currency, 'PENDING', :provider, true, LOCALTIMESTAMP + make_interval(mins => :ttl)) "
                        + "RETURNING " + COLUMNS.replace("o.", ""))
                .bind("guest", guestId)
                .bind("code", guestCode)
                .bind("exam", examId)
                .bind("amount", amountMinor)
                .bind("currency", currency)
                .bind("provider", provider)
                .bind("ttl", ttlMinutes)
                .map(OrderRepository::order)
                .one();
    }

    public Mono<Order> setProviderRef(Long orderId, String providerRef) {
        return databaseClient.sql("UPDATE orders o SET provider_ref = :ref, updated_at = LOCALTIMESTAMP WHERE o.id = :id RETURNING " + COLUMNS)
                .bind("id", orderId)
                .bind("ref", providerRef)
                .map(OrderRepository::order)
                .one();
    }

    public Mono<Order> find(Long orderId) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM orders o WHERE o.id = :id")
                .bind("id", orderId)
                .map(OrderRepository::order)
                .one();
    }

    /** The order with a row lock until the end of the transaction */
    public Mono<Order> lock(Long orderId) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM orders o WHERE o.id = :id FOR UPDATE")
                .bind("id", orderId)
                .map(OrderRepository::order)
                .one();
    }

    public Mono<Order> findByNo(String orderNo) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM orders o WHERE o.order_no = :no")
                .bind("no", orderNo)
                .map(OrderRepository::order)
                .one();
    }

    public Mono<Order> lockByNo(String orderNo) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM orders o WHERE o.order_no = :no FOR UPDATE")
                .bind("no", orderNo)
                .map(OrderRepository::order)
                .one();
    }

    public Mono<Order> findOwn(Long orderId, UUID guestId) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM orders o WHERE o.id = :id AND o.guest_id = :guest")
                .bind("id", orderId)
                .bind("guest", guestId)
                .map(OrderRepository::order)
                .one();
    }

    public Flux<Order> findOwnAll(UUID guestId, int limit) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM orders o WHERE o.guest_id = :guest ORDER BY o.created_at DESC, o.id DESC LIMIT :limit")
                .bind("guest", guestId)
                .bind("limit", limit)
                .map(OrderRepository::order)
                .all();
    }

    /** PENDING orders older than the given time (the reconcile asks their providers) */
    public Flux<Order> findPendingOlderThan(LocalDateTime before, int limit) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM orders o WHERE o.status = 'PENDING' AND o.created_at < :before "
                        + "ORDER BY o.created_at LIMIT :limit")
                .bind("before", before)
                .bind("limit", limit)
                .map(OrderRepository::order)
                .all();
    }

    public Flux<Order> findPaidOfExam(Long examId) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM orders o WHERE o.exam_id = :exam "
                        + "AND o.status IN ('PAID', 'REFUND_PENDING', 'NEEDS_REVIEW', 'PENDING') ORDER BY o.id")
                .bind("exam", examId)
                .map(OrderRepository::order)
                .all();
    }

    /**
     * Moves the order to a status (the trigger refuses a direction that is not allowed), with the fields of that status:
     * paid_at for PAID, refunded_at for REFUNDED, refund_requested_at for REFUND_PENDING; reason goes into review_reason
     * for NEEDS_REVIEW and refund_reason for REFUND_PENDING and REFUNDED. Part of the caller's transaction.
     */
    public Mono<Order> move(Long orderId, OrderStatus to, String reason) {
        String extra = switch (to) {
            case PAID -> ", paid_at = coalesce(o.paid_at, LOCALTIMESTAMP)";
            case REFUNDED -> ", refunded_at = LOCALTIMESTAMP, refund_reason = coalesce(:reason, o.refund_reason)";
            case REFUND_PENDING -> ", refund_requested_at = LOCALTIMESTAMP, refund_reason = coalesce(:reason, o.refund_reason)";
            case NEEDS_REVIEW -> ", review_reason = coalesce(:reason, o.review_reason)";
            default -> "";
        };
        DatabaseClient.GenericExecuteSpec sql = databaseClient.sql("UPDATE orders o SET status = :to" + extra + " WHERE o.id = :id RETURNING " + COLUMNS)
                .bind("id", orderId)
                .bind("to", to.name());
        if (extra.contains(":reason")) {
            sql = reason == null ? sql.bindNull("reason", String.class) : sql.bind("reason", reason);
        }
        return sql.map(OrderRepository::order).one();
    }

    /** Gives the order's seat back, once: true when it held one. Part of the caller's transaction. */
    public Mono<Boolean> releaseSeat(Long orderId) {
        return databaseClient.sql("""
                        WITH freed AS (
                            UPDATE orders SET seat_held = false WHERE id = :id AND seat_held RETURNING exam_id
                        )
                        UPDATE exams e SET seats_taken = GREATEST(0, e.seats_taken - 1) FROM freed WHERE e.id = freed.exam_id
                        """)
                .bind("id", orderId)
                .fetch().rowsUpdated()
                .map(updated -> updated > 0);
    }

    static Order order(Readable row) {
        return new Order(
                row.get("id", Long.class),
                row.get("order_no", String.class),
                row.get("guest_id", UUID.class),
                row.get("guest_code", Integer.class),
                row.get("exam_id", Long.class),
                row.get("amount_minor", Integer.class),
                row.get("currency", String.class),
                OrderStatus.valueOf(row.get("status", String.class)),
                row.get("provider", String.class),
                row.get("provider_ref", String.class),
                Boolean.TRUE.equals(row.get("seat_held", Boolean.class)),
                row.get("review_reason", String.class),
                row.get("refund_reason", String.class),
                row.get("created_at", LocalDateTime.class),
                row.get("updated_at", LocalDateTime.class),
                row.get("expires_at", LocalDateTime.class),
                row.get("paid_at", LocalDateTime.class),
                row.get("refund_requested_at", LocalDateTime.class),
                row.get("refunded_at", LocalDateTime.class));
    }
}
