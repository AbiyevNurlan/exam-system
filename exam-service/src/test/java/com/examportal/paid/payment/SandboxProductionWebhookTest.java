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
import com.examportal.paid.provider.SandboxProductionAudit;
import com.examportal.paid.provider.SandboxProductionGuard;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sandbox in production on a real PostgreSQL. Profile "production" with PAYMENTS_SANDBOX_ALLOWED=true: a correctly
 * signed sandbox notification is 404, writes no payment event and leaves the order PENDING without access; no audit row.
 * With PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION=I-UNDERSTAND the same notification makes the order PAID and the start writes
 * one PAYMENTS_SANDBOX_IN_PRODUCTION row to group_audit_log. In development a closed sandbox still answers 401
 * SIGNATURE_INVALID with its event row, as before. Skipped without Docker.
 */
class SandboxProductionWebhookTest {

    private static final String SECRET = "secret-secret-secret";
    private static PaidTestDatabase database;
    private static SeatReservation seats;

    @BeforeAll
    static void start() {
        database = PaidTestDatabase.start();
        seats = new SeatReservation(new PaidExamRepository(database.db), new OrderRepository(database.db),
                new EntitlementRepository(database.db), database.tx);
    }

    @AfterAll
    static void stop() {
        if (database != null) {
            database.close();
        }
    }

    private static PaymentWebhookService webhooks(SandboxPaymentProvider sandbox) {
        OrderRepository orders = new OrderRepository(database.db);
        EntitlementRepository entitlements = new EntitlementRepository(database.db);
        PaymentEventRepository events = new PaymentEventRepository(database.db);
        PaymentProviders providers = new PaymentProviders(List.of(sandbox, new ManualPaymentProvider()), "SANDBOX");
        PaidNotifications notifications = new PaidNotifications();
        OrderRefunds refunds = new OrderRefunds(orders, entitlements, events, providers, database.tx, notifications);
        return new PaymentWebhookService(providers, orders, entitlements, new PaidExamRepository(database.db), events, refunds,
                notifications, database.tx);
    }

    private static Order order(String title) {
        long exam = database.paidExam(title, 2500, 5);
        return seats.reserve(UUID.randomUUID(), 4_141_592, exam, "SANDBOX").block().order();
    }

    private static HttpHeaders signed(SandboxPaymentProvider.SignedNotification note) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(SandboxPaymentProvider.SIGNATURE_HEADER, note.signature());
        return headers;
    }

    private static String status(Order order) {
        return database.db.sql("SELECT status FROM orders WHERE id = " + order.id()).map(row -> row.get(0, String.class)).one().block();
    }

    private static long auditRows() {
        return database.scalar("SELECT count(*) FROM group_audit_log WHERE action = '" + SandboxProductionAudit.ACTION + "'");
    }

    @Test
    void inProductionASandboxNotificationIs404AndChangesNothing() {
        SandboxProductionGuard guard = new SandboxProductionGuard(true, true, "SANDBOX", true, "");
        // The notification is signed with the same secret: only the guard refuses it
        SandboxPaymentProvider closed = new SandboxPaymentProvider(true, SECRET, "", guard);
        SandboxPaymentProvider signer = new SandboxPaymentProvider(true, SECRET, "");
        Order order = order("T-sandbox-guard closed");
        var note = signer.notification(PaymentProvider.EventType.PAYMENT_SUCCEEDED, order.orderNo(), 2500, "AZN", "sbx_1");
        long eventsBefore = database.scalar("SELECT count(*) FROM payment_events");

        assertThatThrownBy(() -> webhooks(closed).handle("sandbox", note.body(), signed(note)).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(database.scalar("SELECT count(*) FROM payment_events")).isEqualTo(eventsBefore);
        assertThat(status(order)).isEqualTo("PENDING");
        assertThat(database.scalar("SELECT count(*) FROM entitlements WHERE order_id = " + order.id())).isZero();

        long before = auditRows();
        new SandboxProductionAudit(guard, database.db).record().block();
        assertThat(auditRows()).isEqualTo(before);
    }

    @Test
    void withTheOverrideTheSandboxWorksAndEveryStartIsAudited() {
        SandboxProductionGuard guard = new SandboxProductionGuard(true, true, "SANDBOX", true, SandboxProductionGuard.OVERRIDE);
        SandboxPaymentProvider on = new SandboxPaymentProvider(true, SECRET, "", guard);
        Order order = order("T-sandbox-guard override");
        var note = on.notification(PaymentProvider.EventType.PAYMENT_SUCCEEDED, order.orderNo(), 2500, "AZN", "sbx_2");

        assertThat(webhooks(on).handle("sandbox", note.body(), signed(note)).block().outcome()).isEqualTo(PaymentWebhookService.Outcome.PAID);
        assertThat(status(order)).isEqualTo("PAID");

        long before = auditRows();
        SandboxProductionAudit audit = new SandboxProductionAudit(guard, database.db);
        audit.record().block();
        audit.record().block();
        assertThat(auditRows()).isEqualTo(before + 2);
        String details = database.db.sql("SELECT details FROM group_audit_log WHERE action = '" + SandboxProductionAudit.ACTION
                        + "' ORDER BY id DESC LIMIT 1").map(row -> row.get(0, String.class)).one().block();
        assertThat(details).contains("I-UNDERSTAND").contains("no money moves");
        assertThat(database.scalar("SELECT count(*) FROM group_audit_log WHERE action = '" + SandboxProductionAudit.ACTION
                + "' AND actor_id <> '00000000-0000-0000-0000-000000000000'")).isZero();
    }

    @Test
    void inDevelopmentAClosedSandboxIsStill401WithItsEventRow() {
        SandboxPaymentProvider closed = new SandboxPaymentProvider(false, SECRET, "", SandboxProductionGuard.development());
        SandboxPaymentProvider signer = new SandboxPaymentProvider(true, SECRET, "");
        Order order = order("T-sandbox-guard development");
        var note = signer.notification(PaymentProvider.EventType.PAYMENT_SUCCEEDED, order.orderNo(), 2500, "AZN", "sbx_3");
        long invalidBefore = database.scalar("SELECT count(*) FROM payment_events WHERE signature_valid = false");

        assertThatThrownBy(() -> webhooks(closed).handle("sandbox", note.body(), signed(note)).block())
                .isInstanceOfSatisfying(ApplicationException.class, error -> assertThat(error.getErrorCode()).isEqualTo("SIGNATURE_INVALID"));
        assertThat(database.scalar("SELECT count(*) FROM payment_events WHERE signature_valid = false")).isEqualTo(invalidBefore + 1);
        assertThat(status(order)).isEqualTo("PENDING");

        // An open development sandbox pays as before, and nothing is audited outside production
        SandboxPaymentProvider open = new SandboxPaymentProvider(true, SECRET, "", SandboxProductionGuard.development());
        assertThat(webhooks(open).handle("sandbox", note.body(), signed(note)).block().outcome()).isEqualTo(PaymentWebhookService.Outcome.PAID);
        long before = auditRows();
        new SandboxProductionAudit(new SandboxProductionGuard(false, true, "SANDBOX", true, SandboxProductionGuard.OVERRIDE), database.db)
                .record().block();
        assertThat(auditRows()).isEqualTo(before);
    }
}
