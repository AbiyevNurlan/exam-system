package com.examportal.paid.payment;

import com.examportal.common.exception.ApplicationException;
import com.examportal.exam.history.ChangeContext;
import com.examportal.exam.history.ExamHistoryService;
import com.examportal.exam.history.RevisionDraft;
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
import com.examportal.paid.security.PaidGuest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B13b item 10 on a real PostgreSQL: the guest asks for a refund only of a paid order, before the deadline and
 * without an attempt; the administrator refunds with a reason (ATTEMPT_STARTED without override once the guest
 * started), the entitlement is revoked and the seat given back; cancelling a paid open exam writes CANCELLED into
 * its history, cancels the open orders, refunds the paid ones and revokes every entitlement. Skipped without Docker.
 */
class RefundTest {

    private static PaidTestDatabase database;
    private static SandboxPaymentProvider sandbox;
    private static PaymentWebhookService webhooks;
    private static SeatReservation seats;
    private static RefundService refunds;
    private static final ExamHistoryService history = mock(ExamHistoryService.class);

    @BeforeAll
    static void start() {
        database = PaidTestDatabase.start();
        when(history.record(any(), any(), any(), any(), anyList())).thenReturn(Mono.empty());
        sandbox = new SandboxPaymentProvider(true, "secret-secret-secret", "");
        OrderRepository orders = new OrderRepository(database.db);
        EntitlementRepository entitlements = new EntitlementRepository(database.db);
        PaidExamRepository exams = new PaidExamRepository(database.db);
        PaymentEventRepository events = new PaymentEventRepository(database.db);
        PaymentProviders providers = new PaymentProviders(List.of(sandbox, new ManualPaymentProvider()), "SANDBOX");
        PaidNotifications notifications = new PaidNotifications();
        OrderRefunds orderRefunds = new OrderRefunds(orders, entitlements, events, providers, database.tx, notifications);
        webhooks = new PaymentWebhookService(providers, orders, entitlements, exams, events, orderRefunds, notifications, database.tx);
        seats = new SeatReservation(exams, orders, entitlements, database.tx);
        refunds = new RefundService(orders, exams, events, orderRefunds, history, notifications, database.db, database.tx);
    }

    @AfterAll
    static void stop() {
        if (database != null) {
            database.close();
        }
    }

    private static Order paid(long exam, UUID guest) {
        Order order = seats.reserve(guest, 4_141_592, exam, "SANDBOX").block().order();
        var note = sandbox.notification(PaymentProvider.EventType.PAYMENT_SUCCEEDED, order.orderNo(), order.amountMinor(), "AZN", "sbx");
        HttpHeaders headers = new HttpHeaders();
        headers.add(SandboxPaymentProvider.SIGNATURE_HEADER, note.signature());
        webhooks.handle("SANDBOX", note.body(), headers).block();
        return order;
    }

    private static String status(Order order) {
        return database.db.sql("SELECT status FROM orders WHERE id = " + order.id()).map(row -> row.get(0, String.class)).one().block();
    }

    private static String code(Throwable error) {
        return ((ApplicationException) error).getErrorCode();
    }

    private static void startedBy(UUID guest, long exam) {
        database.db.sql("INSERT INTO exam_sessions (session_id, exam_id, user_id, start_time, end_time, status, created_at, updated_at, version) "
                + "VALUES ('" + UUID.randomUUID() + "', " + exam + ", '" + guest + "', LOCALTIMESTAMP, LOCALTIMESTAMP, 'IN_PROGRESS', LOCALTIMESTAMP, LOCALTIMESTAMP, 0)")
                .then().block();
    }

    @Test
    void theGuestAsksAndTheAdministratorRefunds() {
        long exam = database.paidExam("T-b13b refund", 3000, 5);
        UUID guest = UUID.randomUUID();
        Order order = paid(exam, guest);
        assertThat(status(order)).isEqualTo("PAID");

        // Another guest's order is unknown; a pending order cannot be refunded
        assertThatThrownBy(() -> refunds.requestByGuest(new PaidGuest(UUID.randomUUID(), 1), order.id()).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("ORDER_NOT_FOUND"));
        Order pending = seats.reserve(UUID.randomUUID(), 1_234_567, exam, "SANDBOX").block().order();
        assertThatThrownBy(() -> refunds.requestByGuest(new PaidGuest(pending.guestId(), 1), pending.id()).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("REFUND_NOT_ALLOWED"));

        assertThat(refunds.requestByGuest(new PaidGuest(guest, 4_141_592), order.id()).block().status().name()).isEqualTo("REFUND_PENDING");
        assertThatThrownBy(() -> refunds.refundByAdmin(UUID.randomUUID(), order.id(), " ", false).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("REASON_REQUIRED"));
        assertThat(refunds.refundByAdmin(UUID.randomUUID(), order.id(), "Namizəd imtina etdi", false).block().status().name()).isEqualTo("REFUNDED");
        assertThat(database.scalar("SELECT count(*) FROM entitlements WHERE order_id = " + order.id() + " AND status = 'REVOKED'")).isEqualTo(1);
        assertThat(database.scalar("SELECT count(*) FROM payment_events WHERE order_id = " + order.id() + " AND event_type = 'ADMIN_REFUND'")).isEqualTo(1);
        // One seat left held: the pending order
        assertThat(database.scalar("SELECT seats_taken FROM exams WHERE id = " + exam)).isEqualTo(1);
    }

    @Test
    void lateRequestsAndStartedAttempts() {
        long exam = database.paidExam("T-b13b refund late", 3000, null);
        UUID late = UUID.randomUUID();
        Order lateOrder = paid(exam, late);
        UUID started = UUID.randomUUID();
        Order startedOrder = paid(exam, started);
        startedBy(started, exam);
        assertThatThrownBy(() -> refunds.requestByGuest(new PaidGuest(started, 1), startedOrder.id()).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("REFUND_NOT_ALLOWED"));
        // The exam starts in 10 hours, the deadline is 24 hours before
        database.db.sql("UPDATE exams SET start_time = LOCALTIMESTAMP + interval '10 hours' WHERE id = " + exam).then().block();
        assertThatThrownBy(() -> refunds.requestByGuest(new PaidGuest(late, 1), lateOrder.id()).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("REFUND_NOT_ALLOWED"));

        assertThatThrownBy(() -> refunds.refundByAdmin(UUID.randomUUID(), startedOrder.id(), "İmtahan zamanı problem", false).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("ATTEMPT_STARTED"));
        assertThat(refunds.refundByAdmin(UUID.randomUUID(), startedOrder.id(), "İmtahan zamanı texniki problem", true).block().status().name())
                .isEqualTo("REFUNDED");
        assertThat(status(lateOrder)).isEqualTo("PAID");
    }

    @Test
    void cancellingAPaidExamRefundsEveryBuyer() {
        long exam = database.paidExam("T-b13b cancel", 2000, 10);
        Order one = paid(exam, UUID.randomUUID());
        Order two = paid(exam, UUID.randomUUID());
        Order open = seats.reserve(UUID.randomUUID(), 1_234_567, exam, "SANDBOX").block().order();
        UUID gifted = UUID.randomUUID();
        database.db.sql("INSERT INTO entitlements (guest_id, exam_id, status, attempts_left, granted_by, grant_reason) VALUES ('" + gifted + "', "
                + exam + ", 'ACTIVE', 1, '" + UUID.randomUUID() + "', 'test')").then().block();

        ChangeContext admin = ChangeContext.of(UUID.randomUUID(), "ADMIN");
        RefundService.CancelSummary summary = refunds.cancelExam(exam, "Zal hazır deyil", admin).block();
        assertThat(summary.cancelledOrders()).isEqualTo(1);
        assertThat(summary.refunded()).isEqualTo(2);
        assertThat(summary.refundsFailed()).isZero();
        assertThat(status(one)).isEqualTo("REFUNDED");
        assertThat(status(two)).isEqualTo("REFUNDED");
        assertThat(status(open)).isEqualTo("CANCELLED");
        assertThat(database.scalar("SELECT count(*) FROM entitlements WHERE exam_id = " + exam + " AND status = 'ACTIVE'")).isZero();
        assertThat(database.scalar("SELECT seats_taken FROM exams WHERE id = " + exam)).isZero();
        assertThat(database.db.sql("SELECT status FROM exams WHERE id = " + exam).map(row -> row.get(0, String.class)).one().block()).isEqualTo("CANCELLED");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RevisionDraft>> drafts = ArgumentCaptor.forClass(List.class);
        verify(history).record(eq(exam), any(), any(), any(), drafts.capture());
        assertThat(drafts.getValue().get(0).action()).isEqualTo("CANCELLED");
        // Twice is refused; an exam that is not PAID_OPEN is not found here
        assertThatThrownBy(() -> refunds.cancelExam(exam, "again", admin).block()).satisfies(error -> assertThat(code(error)).isEqualTo("EXAM_NOT_CHANGEABLE"));
        long audience = database.exam("T-b13b audience");
        assertThatThrownBy(() -> refunds.cancelExam(audience, "not paid", admin).block()).satisfies(error -> assertThat(code(error)).isEqualTo("EXAM_NOT_FOUND"));
    }
}
