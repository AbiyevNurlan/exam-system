package com.examportal.paid.order;

import com.examportal.paid.PaidTestDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B13b item 3 on a real PostgreSQL with every migration: the state machine of an order is the same in Java
 * (OrderStatus) and in the database (trigger of V24) for all 64 pairs; payment_events cannot be changed or deleted
 * (only with the purge setting of the test-data cleanup); an event id counts once among valid events, and an invalid
 * event with the same id does not block the real one; one ACTIVE entitlement per guest and exam; one PENDING order per
 * guest and exam. Skipped without Docker.
 */
class OrderModelTest {

    private static PaidTestDatabase database;
    private static long examId;

    @BeforeAll
    static void start() {
        database = PaidTestDatabase.start();
        examId = database.exam("T-b13b model");
    }

    @AfterAll
    static void stop() {
        if (database != null) {
            database.close();
        }
    }

    private static long order(OrderStatus status) {
        return database.db.sql("INSERT INTO orders (order_no, guest_id, guest_code, exam_id, amount_minor, currency, status, provider, expires_at) "
                        + "VALUES ('T-' || nextval('order_no_seq'), :guest, 1234567, :exam, 1500, 'AZN', :status, 'SANDBOX', LOCALTIMESTAMP + interval '30 minutes') RETURNING id")
                .bind("guest", UUID.randomUUID()).bind("exam", examId).bind("status", status.name())
                .map(row -> row.get(0, Long.class)).one().block();
    }

    private static boolean move(long orderId, OrderStatus to) {
        try {
            database.db.sql("UPDATE orders SET status = :to WHERE id = :id").bind("to", to.name()).bind("id", orderId).then().block();
            return true;
        } catch (RuntimeException refused) {
            assertThat(refused).hasMessageContaining("cannot go from");
            return false;
        }
    }

    @Test
    void javaAndTheDatabaseAgreeOnEveryTransition() {
        List<String> different = new ArrayList<>();
        int allowed = 0;
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                boolean database = move(order(from), to);
                if (database != from.canMoveTo(to)) {
                    different.add(from + " -> " + to + ": database " + database + ", Java " + from.canMoveTo(to));
                }
                if (database && from != to) {
                    allowed++;
                }
            }
        }
        assertThat(different).isEmpty();
        // 5 from PENDING, 1 each from FAILED, EXPIRED, CANCELLED, 2 from PAID, 2 from REFUND_PENDING, 3 from NEEDS_REVIEW
        assertThat(allowed).isEqualTo(15);
        assertThat(OrderStatus.REFUNDED.canMoveTo(OrderStatus.PAID)).isFalse();
        assertThat(OrderStatus.PAID.canMoveTo(OrderStatus.PENDING)).isFalse();
        assertThat(OrderStatus.EXPIRED.canMoveTo(OrderStatus.PAID)).isFalse();
    }

    @Test
    void paymentEventsAreAppendOnly() {
        long orderId = order(OrderStatus.PENDING);
        long eventId = database.db.sql("INSERT INTO payment_events (order_id, provider, provider_event_id, event_type, signature_valid, amount_minor, currency) "
                        + "VALUES (:order, 'SANDBOX', 'evt-append-1', 'PAYMENT_SUCCEEDED', true, 1500, 'AZN') RETURNING id")
                .bind("order", orderId).map(row -> row.get(0, Long.class)).one().block();
        assertThatThrownBy(() -> database.db.sql("UPDATE payment_events SET amount_minor = 1 WHERE id = " + eventId).then().block())
                .hasMessageContaining("append only");
        assertThatThrownBy(() -> database.db.sql("DELETE FROM payment_events WHERE id = " + eventId).then().block())
                .hasMessageContaining("append only");
        // The same id again from the provider (a valid signature): refused by the unique index
        assertThatThrownBy(() -> database.db.sql("INSERT INTO payment_events (provider, provider_event_id, event_type, signature_valid) "
                + "VALUES ('SANDBOX', 'evt-append-1', 'PAYMENT_SUCCEEDED', true)").then().block()).hasMessageContaining("ux_payment_events_provider_event");
        // A forged event with an id of its own does not take that id away from the real event later
        database.db.sql("INSERT INTO payment_events (provider, provider_event_id, event_type, signature_valid) "
                + "VALUES ('SANDBOX', 'evt-future', 'PAYMENT_SUCCEEDED', false)").then().block();
        database.db.sql("INSERT INTO payment_events (provider, provider_event_id, event_type, signature_valid) "
                + "VALUES ('SANDBOX', 'evt-future', 'PAYMENT_SUCCEEDED', true)").then().block();
        // Only the cleanup of test data may delete, inside its own transaction with the setting
        database.tx.transactional(database.db.sql("SELECT set_config('exam.purge_payment_events', 'on', true)").then()
                .then(database.db.sql("DELETE FROM payment_events WHERE id = " + eventId).then())).block();
        assertThat(database.scalar("SELECT count(*) FROM payment_events WHERE id = " + eventId)).isZero();
        assertThat(database.scalar("SELECT count(*) FROM payment_events WHERE provider_event_id = 'evt-future'")).isEqualTo(2);
    }

    @Test
    void oneActiveEntitlementAndOnePendingOrderPerGuestAndExam() {
        UUID guest = UUID.randomUUID();
        String insert = "INSERT INTO entitlements (guest_id, exam_id, status, attempts_left, granted_by, grant_reason) "
                + "VALUES ('" + guest + "', " + examId + ", 'ACTIVE', 1, '" + UUID.randomUUID() + "', 'test')";
        database.db.sql(insert).then().block();
        assertThatThrownBy(() -> database.db.sql(insert).then().block()).hasMessageContaining("ux_entitlements_active_guest_exam");
        database.db.sql("UPDATE entitlements SET status = 'USED', attempts_left = 0 WHERE guest_id = '" + guest + "'").then().block();
        database.db.sql(insert).then().block();
        // An entitlement comes from an order or from an administrator with a reason
        assertThatThrownBy(() -> database.db.sql("INSERT INTO entitlements (guest_id, exam_id, attempts_left) VALUES ('"
                + UUID.randomUUID() + "', " + examId + ", 1)").then().block()).hasMessageContaining("ck_entitlements_source");

        String pending = "INSERT INTO orders (order_no, guest_id, guest_code, exam_id, amount_minor, currency, provider, expires_at) "
                + "VALUES ('T-' || nextval('order_no_seq'), '" + guest + "', 1234567, " + examId + ", 1500, 'AZN', 'SANDBOX', LOCALTIMESTAMP)";
        database.db.sql(pending).then().block();
        assertThatThrownBy(() -> database.db.sql(pending).then().block()).hasMessageContaining("ux_orders_pending_guest_exam");
        // Money is whole minor units above zero
        assertThatThrownBy(() -> database.db.sql("INSERT INTO orders (order_no, guest_id, guest_code, exam_id, amount_minor, currency, provider, expires_at) "
                + "VALUES ('T-x', '" + UUID.randomUUID() + "', 1234567, " + examId + ", 0, 'AZN', 'SANDBOX', LOCALTIMESTAMP)").then().block())
                .hasMessageContaining("ck_orders_amount");
    }
}
