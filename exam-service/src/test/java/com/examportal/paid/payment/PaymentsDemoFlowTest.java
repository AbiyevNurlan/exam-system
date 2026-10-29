package com.examportal.paid.payment;

import com.examportal.paid.PaidTestDatabase;
import com.examportal.paid.catalog.PaidCatalogService;
import com.examportal.paid.exam.PaidExamRepository;
import com.examportal.paid.guest.GuestDirectory;
import com.examportal.paid.order.AdminOrderRepository;
import com.examportal.paid.order.AdminOrderService;
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
import com.examportal.paid.web.PaidDtos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * PAYMENTS_DEMO_MODE on a real PostgreSQL. Production in DEMO: the checkout of a new order is the sandbox, its signed
 * notification makes the order PAID with access, every start writes PAYMENTS_DEMO_MODE_ON, the catalog says
 * paymentsDemo. Production without DEMO: the same notification is 404 and the catalog says no demo. The administrator
 * sees DEMO orders apart from real money: demo per row, the demo filter, the summary's real and demo figures, the CSV's
 * Provayder and Demo columns; a DEMO order paid by hand becomes MANUAL (real money). Skipped without Docker.
 */
class PaymentsDemoFlowTest {

    private static final String SECRET = "secret-secret-secret";
    private static PaidTestDatabase database;
    private static SeatReservation seats;
    private static AdminOrderService admin;

    @BeforeAll
    static void start() {
        database = PaidTestDatabase.start();
        OrderRepository orders = new OrderRepository(database.db);
        PaidExamRepository exams = new PaidExamRepository(database.db);
        EntitlementRepository entitlements = new EntitlementRepository(database.db);
        seats = new SeatReservation(exams, orders, entitlements, database.tx);
        admin = new AdminOrderService(new AdminOrderRepository(database.db), orders, entitlements, new PaymentEventRepository(database.db),
                exams, mock(GuestDirectory.class), new PaidNotifications(), database.tx);
    }

    @AfterAll
    static void stop() {
        if (database != null) {
            database.close();
        }
    }

    private static SandboxProductionGuard demo() {
        return new SandboxProductionGuard(true, true, "SANDBOX", true, null, true);
    }

    private static PaymentProviders providers(SandboxPaymentProvider sandbox) {
        return new PaymentProviders(List.of(sandbox, new ManualPaymentProvider()), "SANDBOX");
    }

    private static PaymentWebhookService webhooks(SandboxPaymentProvider sandbox) {
        OrderRepository orders = new OrderRepository(database.db);
        EntitlementRepository entitlements = new EntitlementRepository(database.db);
        PaymentEventRepository events = new PaymentEventRepository(database.db);
        PaymentProviders providers = providers(sandbox);
        PaidNotifications notifications = new PaidNotifications();
        return new PaymentWebhookService(providers, orders, entitlements, new PaidExamRepository(database.db), events,
                new OrderRefunds(orders, entitlements, events, providers, database.tx, notifications), notifications, database.tx);
    }

    /** The order and its payment as the DEMO pays it: checkout of the sandbox, then its signed success notification */
    private static Order payInDemo(SandboxPaymentProvider sandbox, long exam, int code) {
        PaymentProvider checkout = providers(sandbox).forCheckout().block();
        assertThat(checkout).isSameAs(sandbox);
        Order order = seats.reserve(UUID.randomUUID(), code, exam, checkout.name()).block().order();
        assertThat(sandbox.createCheckout(order).block().checkoutUrl()).contains("/api/payments/sandbox/checkout/" + order.orderNo());
        var note = sandbox.notification(PaymentProvider.EventType.PAYMENT_SUCCEEDED, order.orderNo(), order.amountMinor(), "AZN", "sbx_demo");
        HttpHeaders headers = new HttpHeaders();
        headers.add(SandboxPaymentProvider.SIGNATURE_HEADER, note.signature());
        assertThat(webhooks(sandbox).handle("sandbox", note.body(), headers).block().outcome()).isEqualTo(PaymentWebhookService.Outcome.PAID);
        return order;
    }

    private static String status(Order order) {
        return database.db.sql("SELECT status FROM orders WHERE id = " + order.id()).map(row -> row.get(0, String.class)).one().block();
    }

    @Test
    void inProductionTheDemoPaysTheWholeWay() {
        SandboxProductionGuard guard = demo();
        SandboxPaymentProvider sandbox = new SandboxPaymentProvider(true, SECRET, "", guard);
        long exam = database.paidExam("T-demo whole way", 3000, 5);

        Order order = payInDemo(sandbox, exam, 2_000_001);
        assertThat(status(order)).isEqualTo("PAID");
        assertThat(database.scalar("SELECT count(*) FROM entitlements WHERE order_id = " + order.id() + " AND status = 'ACTIVE'")).isEqualTo(1);

        long before = database.scalar("SELECT count(*) FROM group_audit_log WHERE action = '" + SandboxProductionAudit.DEMO_ACTION + "'");
        new SandboxProductionAudit(guard, database.db).record().block();
        assertThat(database.scalar("SELECT count(*) FROM group_audit_log WHERE action = '" + SandboxProductionAudit.DEMO_ACTION + "'"))
                .isEqualTo(before + 1);
        assertThat(database.scalar("SELECT count(*) FROM group_audit_log WHERE action = '" + SandboxProductionAudit.ACTION
                + "' AND details LIKE '%DEMO%'")).isZero();

        PaidCatalogService catalog = new PaidCatalogService(new PaidExamRepository(database.db), guard);
        PaidDtos.CatalogPage page = catalog.page(0, 50).block();
        assertThat(page.paymentsDemo()).isTrue();
        assertThat(page.content()).isNotEmpty().allSatisfy(item -> assertThat(item.paymentsDemo()).isTrue());
        assertThat(catalog.one(exam).block().paymentsDemo()).isTrue();
    }

    @Test
    void inProductionWithoutTheDemoNothingChanges() {
        SandboxProductionGuard closed = new SandboxProductionGuard(true, true, "SANDBOX", true, null, false);
        SandboxPaymentProvider sandbox = new SandboxPaymentProvider(true, SECRET, "", closed);
        long exam = database.paidExam("T-demo closed", 3000, 5);
        Order order = seats.reserve(UUID.randomUUID(), 2_000_002, exam, "SANDBOX").block().order();
        var note = new SandboxPaymentProvider(true, SECRET, "").notification(PaymentProvider.EventType.PAYMENT_SUCCEEDED, order.orderNo(),
                3000, "AZN", "sbx_closed");
        HttpHeaders headers = new HttpHeaders();
        headers.add(SandboxPaymentProvider.SIGNATURE_HEADER, note.signature());
        assertThatThrownBy(() -> webhooks(sandbox).handle("sandbox", note.body(), headers).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(status(order)).isEqualTo("PENDING");
        assertThatThrownBy(() -> providers(sandbox).forCheckout().block()).hasMessageContaining("provider");

        long before = database.scalar("SELECT count(*) FROM group_audit_log WHERE action = '" + SandboxProductionAudit.DEMO_ACTION + "'");
        new SandboxProductionAudit(closed, database.db).record().block();
        assertThat(database.scalar("SELECT count(*) FROM group_audit_log WHERE action = '" + SandboxProductionAudit.DEMO_ACTION + "'"))
                .isEqualTo(before);
        PaidCatalogService catalog = new PaidCatalogService(new PaidExamRepository(database.db), closed);
        assertThat(catalog.page(0, 50).block().paymentsDemo()).isFalse();
        assertThat(catalog.one(exam).block().paymentsDemo()).isFalse();
        // The constructor of before (development, sandbox off): no demo
        assertThat(new PaidCatalogService(new PaidExamRepository(database.db)).one(exam).block().paymentsDemo()).isFalse();
    }

    @Test
    void theAdministratorSeesDemoApartFromRealMoney() {
        SandboxPaymentProvider sandbox = new SandboxPaymentProvider(true, SECRET, "", demo());
        long exam = database.paidExam("T-demo finance", 2500, 10);
        Order demoPaid = payInDemo(sandbox, exam, 2_000_011);
        Order cash = seats.reserve(UUID.randomUUID(), 2_000_012, exam, "SANDBOX").block().order();
        admin.markPaid(UUID.randomUUID(), cash.id(), "Nağd ödəniş kassada", "Q-DEMO-1").block();
        Order pending = seats.reserve(UUID.randomUUID(), 2_000_013, exam, "SANDBOX").block().order();

        // Paid by hand: real money, MANUAL
        assertThat(database.db.sql("SELECT provider FROM orders WHERE id = " + cash.id()).map(row -> row.get(0, String.class)).one().block())
                .isEqualTo("MANUAL");

        PaidDtos.ExamSummary summary = admin.summary(exam).block();
        assertThat(summary.paidCount()).isEqualTo(1);
        assertThat(summary.revenueMinor()).isEqualTo(2500);
        assertThat(summary.demoPaidCount()).isEqualTo(1);
        assertThat(summary.demoAmountMinor()).isEqualTo(2500);
        assertThat(summary.pendingCount()).isEqualTo(1);

        var all = admin.page(new AdminOrderRepository.Filter(null, exam, null, null, null, null), null, null, 0, 20).block();
        assertThat(all.totalElements()).isEqualTo(3);
        assertThat(all.content()).filteredOn(PaidDtos.AdminOrderRow::demo).extracting(PaidDtos.AdminOrderRow::orderNo)
                .containsExactlyInAnyOrder(demoPaid.orderNo(), pending.orderNo());
        var onlyDemo = admin.page(new AdminOrderRepository.Filter(null, exam, null, null, null, null, true), null, null, 0, 20).block();
        assertThat(onlyDemo.content()).extracting(PaidDtos.AdminOrderRow::orderNo).containsExactlyInAnyOrder(demoPaid.orderNo(), pending.orderNo());
        var onlyReal = admin.page(new AdminOrderRepository.Filter(null, exam, null, null, null, null, false), null, null, 0, 20).block();
        assertThat(onlyReal.content()).singleElement().satisfies(row -> {
            assertThat(row.orderNo()).isEqualTo(cash.orderNo());
            assertThat(row.demo()).isFalse();
            assertThat(row.provider()).isEqualTo("MANUAL");
        });
        assertThat(admin.detail(demoPaid.id()).block().order().demo()).isTrue();

        String csv = String.join("", admin.csv(new AdminOrderRepository.Filter(null, exam, null, null, null, null), "orderNo", "asc",
                        UUID.randomUUID()).block()
                .map(buffer -> buffer.toString(StandardCharsets.UTF_8)).collectList().block());
        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).startsWith("﻿").contains(",Provayder,").endsWith(",Demo");
        assertThat(lines).hasSize(4);
        assertThat(csv).contains(demoPaid.orderNo()).contains(cash.orderNo());
        for (String line : List.of(lines).subList(1, 4)) {
            boolean isCash = line.startsWith(cash.orderNo());
            assertThat(line).contains(isCash ? ",MANUAL," : ",SANDBOX,");
            assertThat(line.substring(line.lastIndexOf(',') + 1)).isEqualTo(isCash ? "xeyr" : "bəli");
        }
    }
}
