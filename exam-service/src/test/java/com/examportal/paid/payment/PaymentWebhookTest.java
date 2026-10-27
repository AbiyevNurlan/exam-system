package com.examportal.paid.payment;

import com.examportal.common.exception.ApplicationException;
import com.examportal.paid.PaidTestDatabase;
import com.examportal.paid.exam.PaidExamRepository;
import com.examportal.paid.order.EntitlementRepository;
import com.examportal.paid.order.Order;
import com.examportal.paid.order.OrderRepository;
import com.examportal.paid.order.SeatReservation;
import com.examportal.paid.provider.ManualPaymentProvider;
import com.examportal.paid.provider.PaymentProvider;
import com.examportal.paid.provider.PaymentProviders;
import com.examportal.paid.provider.SandboxPaymentProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B13b item 7 on a real PostgreSQL: a valid notification makes the order PAID, gives the entitlement and writes the
 * event in one transaction; the same notification again, also ten times at once, changes nothing more; a bad
 * signature is 401 and only recorded; a wrong amount is NEEDS_REVIEW without access; a payment for an expired order, a
 * cancelled exam or a guest who may take the exam already is NEEDS_REVIEW and refunded at once; a failed payment
 * gives the seat back; an unknown order is recorded. Skipped without Docker.
 */
class PaymentWebhookTest {

    private static PaidTestDatabase database;
    private static SandboxPaymentProvider sandbox;
    private static PaymentWebhookService webhooks;
    private static SeatReservation seats;

    @BeforeAll
    static void start() {
        database = PaidTestDatabase.start();
        sandbox = new SandboxPaymentProvider(true, "secret-secret-secret", "");
        OrderRepository orders = new OrderRepository(database.db);
        EntitlementRepository entitlements = new EntitlementRepository(database.db);
        PaidExamRepository exams = new PaidExamRepository(database.db);
        PaymentEventRepository events = new PaymentEventRepository(database.db);
        PaymentProviders providers = new PaymentProviders(List.of(sandbox, new ManualPaymentProvider()), "SANDBOX");
        PaidNotifications notifications = new PaidNotifications();
        OrderRefunds refunds = new OrderRefunds(orders, entitlements, events, providers, database.tx, notifications);
        webhooks = new PaymentWebhookService(providers, orders, entitlements, exams, events, refunds, notifications, database.tx);
        seats = new SeatReservation(exams, orders, entitlements, database.tx);
    }

    @AfterAll
    static void stop() {
        if (database != null) {
            database.close();
        }
    }

    private static Order order(long exam, UUID guest) {
        return seats.reserve(guest, 4_141_592, exam, "SANDBOX").block().order();
    }

    private static PaymentWebhookService.Result send(SandboxPaymentProvider.SignedNotification note) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(SandboxPaymentProvider.SIGNATURE_HEADER, note.signature());
        return webhooks.handle("sandbox", note.body(), headers).block();
    }

    private static String status(Order order) {
        return database.db.sql("SELECT status FROM orders WHERE id = " + order.id()).map(row -> row.get(0, String.class)).one().block();
    }

    private static long count(String sql) {
        return database.scalar(sql);
    }

    @Test
    void aValidPaymentGivesAccessOnceHoweverOftenItIsNotified() {
        long exam = database.paidExam("T-b13b webhook paid", 2500, 5);
        Order order = order(exam, UUID.randomUUID());
        var note = sandbox.notification(PaymentProvider.EventType.PAYMENT_SUCCEEDED, order.orderNo(), 2500, "AZN", "sbx_1");

        assertThat(send(note).outcome()).isEqualTo(PaymentWebhookService.Outcome.PAID);
        assertThat(status(order)).isEqualTo("PAID");
        assertThat(count("SELECT count(*) FROM entitlements WHERE order_id = " + order.id() + " AND status = 'ACTIVE' AND attempts_left = 1")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payment_events WHERE order_id = " + order.id() + " AND outcome = 'PAID'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM orders WHERE id = " + order.id() + " AND paid_at IS NOT NULL AND seat_held")).isEqualTo(1);

        // The same notification again, and ten times at once: 200, nothing more
        assertThat(send(note).outcome()).isEqualTo(PaymentWebhookService.Outcome.DUPLICATE);
        List<PaymentWebhookService.Outcome> parallel = Flux.range(0, 10)
                .flatMap(i -> reactor.core.publisher.Mono.fromCallable(() -> send(note).outcome()).subscribeOn(Schedulers.boundedElastic()), 10)
                .collectList().block();
        assertThat(parallel).containsOnly(PaymentWebhookService.Outcome.DUPLICATE);
        assertThat(count("SELECT count(*) FROM payment_events WHERE order_id = " + order.id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM entitlements WHERE order_id = " + order.id())).isEqualTo(1);
    }

    @Test
    void parallelFirstNotificationsGiveOnePayment() {
        long exam = database.paidExam("T-b13b webhook race", 2500, 5);
        Order order = order(exam, UUID.randomUUID());
        var note = sandbox.notification(PaymentProvider.EventType.PAYMENT_SUCCEEDED, order.orderNo(), 2500, "AZN", "sbx_2");
        List<PaymentWebhookService.Outcome> outcomes = Flux.range(0, 10)
                .flatMap(i -> reactor.core.publisher.Mono.fromCallable(() -> send(note).outcome()).subscribeOn(Schedulers.boundedElastic()), 10)
                .collectList().block();
        assertThat(outcomes).filteredOn(PaymentWebhookService.Outcome.PAID::equals).hasSize(1);
        assertThat(count("SELECT count(*) FROM payment_events WHERE order_id = " + order.id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM entitlements WHERE order_id = " + order.id())).isEqualTo(1);
    }

    @Test
    void aBadSignatureIsRefusedAndOnlyRecorded() {
        long exam = database.paidExam("T-b13b webhook forged", 2500, 5);
        Order order = order(exam, UUID.randomUUID());
        var note = sandbox.notification(PaymentProvider.EventType.PAYMENT_SUCCEEDED, order.orderNo(), 2500, "AZN", "sbx_3");
        HttpHeaders forged = new HttpHeaders();
        forged.add(SandboxPaymentProvider.SIGNATURE_HEADER, new SandboxPaymentProvider(true, "attacker-secret-xyz", "").sign(note.body()));
        assertThatThrownBy(() -> webhooks.handle("SANDBOX", note.body(), forged).block())
                .satisfies(error -> {
                    assertThat(((ApplicationException) error).getErrorCode()).isEqualTo("SIGNATURE_INVALID");
                    assertThat(((ApplicationException) error).getStatus().value()).isEqualTo(401);
                });
        assertThat(status(order)).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM payment_events WHERE NOT signature_valid AND outcome = 'SIGNATURE_INVALID'")).isPositive();
        // The real notification afterwards still counts
        assertThat(send(note).outcome()).isEqualTo(PaymentWebhookService.Outcome.PAID);
        // Card fields are never stored
        String withCard = new String(note.body(), StandardCharsets.UTF_8).replace("{", "{\"cardNumber\":\"4111111111111111\",");
        assertThatThrownBy(() -> webhooks.handle("SANDBOX", withCard.getBytes(StandardCharsets.UTF_8), forged).block())
                .isInstanceOf(ApplicationException.class);
        assertThat(count("SELECT count(*) FROM payment_events WHERE payload LIKE '%4111%'")).isZero();
    }

    @Test
    void aWrongAmountNeedsReviewWithoutAccess() {
        long exam = database.paidExam("T-b13b webhook amount", 2500, 5);
        Order order = order(exam, UUID.randomUUID());
        assertThat(send(sandbox.notification(PaymentProvider.EventType.PAYMENT_SUCCEEDED, order.orderNo(), 100, "AZN", "sbx_4")).outcome())
                .isEqualTo(PaymentWebhookService.Outcome.NEEDS_REVIEW_AMOUNT);
        assertThat(status(order)).isEqualTo("NEEDS_REVIEW");
        assertThat(count("SELECT count(*) FROM entitlements WHERE order_id = " + order.id())).isZero();
        Order other = order(exam, UUID.randomUUID());
        assertThat(send(sandbox.notification(PaymentProvider.EventType.PAYMENT_SUCCEEDED, other.orderNo(), 2500, "USD", "sbx_5")).outcome())
                .isEqualTo(PaymentWebhookService.Outcome.NEEDS_REVIEW_AMOUNT);
    }

    @Test
    void aPaymentThatCannotBeHonouredIsRefundedAtOnce() {
        long exam = database.paidExam("T-b13b webhook late", 2500, 5);
        Order expired = order(exam, UUID.randomUUID());
        database.db.sql("UPDATE orders SET expires_at = LOCALTIMESTAMP - interval '1 second' WHERE id = " + expired.id()).then().block();
        database.db.sql("SELECT 1").then().block();
        new OrderRepository(database.db).expireStale(exam).collectList().block();
        assertThat(status(expired)).isEqualTo("EXPIRED");
        assertThat(send(sandbox.notification(PaymentProvider.EventType.PAYMENT_SUCCEEDED, expired.orderNo(), 2500, "AZN", "sbx_6")).outcome())
                .isEqualTo(PaymentWebhookService.Outcome.NEEDS_REVIEW_LATE);
        // NEEDS_REVIEW, then the automatic refund of the sandbox succeeded
        assertThat(status(expired)).isEqualTo("REFUNDED");
        assertThat(count("SELECT count(*) FROM payment_events WHERE order_id = " + expired.id() + " AND event_type = 'REFUND_SUCCEEDED'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM entitlements WHERE order_id = " + expired.id())).isZero();

        // A guest who may take the exam already (given free meanwhile)
        UUID guest = UUID.randomUUID();
        Order second = order(exam, guest);
        database.db.sql("INSERT INTO entitlements (guest_id, exam_id, status, attempts_left, granted_by, grant_reason) VALUES ('" + guest + "', "
                + exam + ", 'ACTIVE', 1, '" + UUID.randomUUID() + "', 'test')").then().block();
        assertThat(send(sandbox.notification(PaymentProvider.EventType.PAYMENT_SUCCEEDED, second.orderNo(), 2500, "AZN", "sbx_7")).outcome())
                .isEqualTo(PaymentWebhookService.Outcome.NEEDS_REVIEW_ALREADY_ENTITLED);
        assertThat(status(second)).isEqualTo("REFUNDED");

        // A cancelled exam
        Order third = order(exam, UUID.randomUUID());
        database.db.sql("UPDATE exams SET status = 'CANCELLED' WHERE id = " + exam).then().block();
        assertThat(send(sandbox.notification(PaymentProvider.EventType.PAYMENT_SUCCEEDED, third.orderNo(), 2500, "AZN", "sbx_8")).outcome())
                .isEqualTo(PaymentWebhookService.Outcome.NEEDS_REVIEW_EXAM_CANCELLED);
        assertThat(status(third)).isEqualTo("REFUNDED");
        // Every seat is back
        assertThat(count("SELECT seats_taken FROM exams WHERE id = " + exam)).isZero();
    }

    @Test
    void aFailedPaymentGivesTheSeatBackAndUnknownOrdersAreRecorded() {
        long exam = database.paidExam("T-b13b webhook failed", 2500, 1);
        Order order = order(exam, UUID.randomUUID());
        assertThat(send(sandbox.notification(PaymentProvider.EventType.PAYMENT_FAILED, order.orderNo(), 2500, "AZN", "sbx_9")).outcome())
                .isEqualTo(PaymentWebhookService.Outcome.FAILED);
        assertThat(status(order)).isEqualTo("FAILED");
        assertThat(count("SELECT seats_taken FROM exams WHERE id = " + exam)).isZero();
        assertThat(send(sandbox.notification(PaymentProvider.EventType.PAYMENT_SUCCEEDED, "YK-000000-999999", 2500, "AZN", "sbx_x")).outcome())
                .isEqualTo(PaymentWebhookService.Outcome.ORDER_UNKNOWN);
        assertThat(count("SELECT count(*) FROM payment_events WHERE order_id IS NULL AND outcome = 'ORDER_UNKNOWN'")).isEqualTo(1);
    }
}
