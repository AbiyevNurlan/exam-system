package com.examportal.paid.payment;

import com.examportal.paid.PaidFeature;
import com.examportal.paid.PaidTestDatabase;
import com.examportal.paid.exam.PaidExamRepository;
import com.examportal.paid.order.EntitlementRepository;
import com.examportal.paid.order.Order;
import com.examportal.paid.order.OrderRepository;
import com.examportal.paid.order.SeatReservation;
import com.examportal.paid.provider.ManualPaymentProvider;
import com.examportal.paid.provider.PaymentProvider;
import com.examportal.paid.provider.PaymentProviders;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B13b item 8 on a real PostgreSQL: the reconcile expires PENDING orders whose time is over and gives their seats
 * back; asks the provider of PENDING orders older than 10 minutes and settles them as the notification would (once:
 * a second run changes nothing); skips its run while another instance holds the advisory lock; does nothing while
 * the feature is off. Skipped without Docker.
 */
class PaidReconcileTest {

    private static PaidTestDatabase database;
    private static final PaymentProvider provider = mock(PaymentProvider.class);
    private static PaidReconcileJob job;
    private static SeatReservation seats;

    @BeforeAll
    static void start() {
        database = PaidTestDatabase.start();
        when(provider.name()).thenReturn("SANDBOX");
        when(provider.available()).thenReturn(true);
        when(provider.verifyWebhook(any(), any(HttpHeaders.class))).thenReturn(PaymentProvider.Verification.invalid(null));
        when(provider.refund(any(), any())).thenReturn(Mono.just(PaymentProvider.RefundOutcome.SUCCEEDED));
        when(provider.fetchStatus(any())).thenAnswer(call -> {
            Order order = call.getArgument(0);
            return Mono.just(order.amountMinor() == 4200
                    ? new PaymentProvider.ProviderStatus(PaymentProvider.PaymentState.PAID, 4200, "AZN", "prov-evt-" + order.orderNo())
                    : PaymentProvider.ProviderStatus.unknown());
        });
        OrderRepository orders = new OrderRepository(database.db);
        EntitlementRepository entitlements = new EntitlementRepository(database.db);
        PaidExamRepository exams = new PaidExamRepository(database.db);
        PaymentEventRepository events = new PaymentEventRepository(database.db);
        PaymentProviders providers = new PaymentProviders(List.of(provider, new ManualPaymentProvider()), "SANDBOX");
        PaidNotifications notifications = new PaidNotifications();
        OrderRefunds refunds = new OrderRefunds(orders, entitlements, events, providers, database.tx, notifications);
        PaymentWebhookService webhooks = new PaymentWebhookService(providers, orders, entitlements, exams, events, refunds, notifications, database.tx);
        job = new PaidReconcileJob(new PaidFeature(true), database.db, orders, providers, webhooks, notifications, database.tx);
        seats = new SeatReservation(exams, orders, entitlements, database.tx);
    }

    @AfterAll
    static void stop() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void expiresOldOrdersAndSettlesWhatTheProviderKnows() {
        long exam = database.paidExam("T-b13b reconcile", 1000, 3);
        Order stale = seats.reserve(UUID.randomUUID(), 1_234_567, exam, "SANDBOX").block().order();
        database.db.sql("UPDATE orders SET expires_at = LOCALTIMESTAMP - interval '1 minute' WHERE id = " + stale.id()).then().block();

        long paidExam = database.paidExam("T-b13b reconcile paid", 4200, null);
        Order unnotified = seats.reserve(UUID.randomUUID(), 1_234_567, paidExam, "SANDBOX").block().order();
        // Older than 10 minutes, still within its 30
        database.db.sql("UPDATE orders SET created_at = LOCALTIMESTAMP - interval '12 minutes' WHERE id = " + unnotified.id()).then().block();

        PaidReconcileJob.Summary first = job.runOnce().block();
        assertThat(first.ran()).isTrue();
        assertThat(first.expired()).isGreaterThanOrEqualTo(1);
        assertThat(first.settled()).isEqualTo(1);
        assertThat(database.scalar("SELECT count(*) FROM orders WHERE id = " + stale.id() + " AND status = 'EXPIRED' AND NOT seat_held")).isEqualTo(1);
        assertThat(database.scalar("SELECT seats_taken FROM exams WHERE id = " + exam)).isZero();
        assertThat(database.scalar("SELECT count(*) FROM orders WHERE id = " + unnotified.id() + " AND status = 'PAID'")).isEqualTo(1);
        assertThat(database.scalar("SELECT count(*) FROM entitlements WHERE order_id = " + unnotified.id())).isEqualTo(1);

        // Again: nothing more happens
        PaidReconcileJob.Summary second = job.runOnce().block();
        assertThat(second.settled()).isZero();
        assertThat(database.scalar("SELECT count(*) FROM payment_events WHERE order_id = " + unnotified.id())).isEqualTo(1);
    }

    @Test
    void anotherInstanceHoldingTheLockSkipsTheRun() throws Exception {
        try (Connection other = DriverManager.getConnection(database.postgres.getJdbcUrl(), database.postgres.getUsername(),
                database.postgres.getPassword()); Statement statement = other.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(" + PaidReconcileJob.LOCK_KEY + ")");
            assertThat(job.runOnce().block().ran()).isFalse();
            statement.execute("SELECT pg_advisory_unlock(" + PaidReconcileJob.LOCK_KEY + ")");
        }
        assertThat(job.runOnce().block().ran()).isTrue();
        // The lock was given back after the run: it can be taken again at once
        assertThat(job.runOnce().block().ran()).isTrue();
    }

    @Test
    void nothingRunsWhileTheFeatureIsOff() {
        PaidReconcileJob off = new PaidReconcileJob(new PaidFeature(false), database.db, null, null, null, null, database.tx);
        assertThat(off.runOnce().block().ran()).isFalse();
    }
}
