package com.examportal.paid.order;

import com.examportal.common.exception.ApplicationException;
import com.examportal.paid.PaidTestDatabase;
import com.examportal.paid.exam.PaidExamRepository;
import com.examportal.paid.guest.GuestDirectory;
import com.examportal.paid.payment.PaidNotifications;
import com.examportal.paid.payment.PaymentEventRepository;
import com.examportal.paid.web.PaidDtos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B13b item 11 on a real PostgreSQL: the administrator's list (filters by status, exam, dates, 7-digit code, order
 * number; sort and direction a whitelist; pages), one order with its events in time order, the CSV (BOM, Azerbaijani
 * headers, the participant code, times in Baku, harmless cells), a MANUAL payment (reason and document required),
 * free access by the code (once), and the figures of a paid exam. Skipped without Docker.
 */
class AdminOrderTest {

    private static PaidTestDatabase database;
    private static AdminOrderService admin;
    private static SeatReservation seats;
    private static long exam;
    private static final UUID KNOWN_GUEST = UUID.randomUUID();

    @BeforeAll
    static void start() {
        database = PaidTestDatabase.start();
        GuestDirectory directory = mock(GuestDirectory.class);
        when(directory.byCode(anyInt())).thenAnswer(call -> (int) call.getArgument(0) == 7_777_777
                ? Mono.just(new GuestDirectory.GuestRef(KNOWN_GUEST, 7_777_777, "g@x.test", "A", "B", false))
                : Mono.error(com.examportal.paid.PaidErrors.guestNotFound()));
        OrderRepository orders = new OrderRepository(database.db);
        PaidExamRepository exams = new PaidExamRepository(database.db);
        EntitlementRepository entitlements = new EntitlementRepository(database.db);
        admin = new AdminOrderService(new AdminOrderRepository(database.db), orders, entitlements, new PaymentEventRepository(database.db),
                exams, directory, new PaidNotifications(), database.tx);
        seats = new SeatReservation(exams, orders, entitlements, database.tx);
        exam = database.paidExam("T-b13b, \"admin\" =list", 1999, 10);
        for (int i = 0; i < 5; i++) {
            seats.reserve(UUID.randomUUID(), 1_000_000 + i, exam, "SANDBOX").block();
        }
    }

    @AfterAll
    static void stop() {
        if (database != null) {
            database.close();
        }
    }

    private static AdminOrderRepository.Filter filter(String status, Long examId, Integer code, String orderNo) {
        return new AdminOrderRepository.Filter(status, examId, null, null, code, orderNo);
    }

    private static String code(Throwable error) {
        return ((ApplicationException) error).getErrorCode();
    }

    @Test
    void theListFiltersSortsAndPages() {
        var all = admin.page(filter(null, exam, null, null), "code", "asc", 0, 2).block();
        assertThat(all.totalElements()).isEqualTo(5);
        assertThat(all.content()).hasSize(2).extracting(PaidDtos.AdminOrderRow::participantCode).containsExactly(1_000_000, 1_000_001);
        assertThat(admin.page(filter(null, exam, null, null), "code", "asc", 2, 2).block().content()).hasSize(1);
        var one = admin.page(filter("pending", exam, 1_000_003, null), null, null, 0, 20).block();
        assertThat(one.content()).singleElement().satisfies(row -> {
            assertThat(row.participantCode()).isEqualTo(1_000_003);
            assertThat(row.amountMinor()).isEqualTo(1999);
            assertThat(row.examTitle()).contains("admin");
        });
        String orderNo = one.content().get(0).orderNo();
        assertThat(admin.page(filter(null, null, null, orderNo.toLowerCase()), null, null, 0, 20).block().totalElements()).isEqualTo(1);
        assertThat(admin.page(new AdminOrderRepository.Filter(null, exam, LocalDateTime.now().plusDays(1), null, null, null), null, null, 0, 20)
                .block().totalElements()).isZero();
        assertThat(admin.page(filter("PAID", exam, null, null), null, null, 0, 20).block().totalElements()).isZero();

        for (String[] wrong : List.of(new String[]{"password", null}, new String[]{"createdAt", "sideways"}, new String[]{"id; DROP TABLE orders", null})) {
            assertThatThrownBy(() -> admin.page(filter(null, exam, null, null), wrong[0], wrong[1], 0, 20).block())
                    .satisfies(error -> assertThat(code(error)).isEqualTo("VALIDATION_ERROR"));
        }
        assertThatThrownBy(() -> admin.page(filter("SOMETHING", exam, null, null), null, null, 0, 20).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("VALIDATION_ERROR"));
        assertThatThrownBy(() -> admin.page(filter(null, exam, 12, null), null, null, 0, 20).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("VALIDATION_ERROR"));
        assertThat(admin.page(filter(null, null, null, null), null, null, 0, 10_000).block().size()).isEqualTo(100);
    }

    @Test
    void theCsvIsTidyAndHarmless() {
        String csv = String.join("", admin.csv(filter(null, exam, null, null), "code", "asc", UUID.randomUUID()).block()
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    return new String(bytes, StandardCharsets.UTF_8);
                }).collectList().block());
        assertThat(csv).startsWith("﻿Sifariş nömrəsi,İştirakçı kodu,İmtahan ID,İmtahan,Məbləğ,Məbləğ (qəpik),Valyuta,Status");
        String[] lines = csv.split("\r\n");
        assertThat(lines).hasSize(6);
        assertThat(lines[1]).contains(",1000000,").contains("\"19,99\"").contains(",1999,AZN,PENDING,SANDBOX,")
                // A title with a comma and quotes is one quoted cell
                .contains("\"T-b13b, \"\"admin\"\" =list\"");
        assertThat(AdminOrderService.cell("=HYPERLINK(\"x\")")).startsWith("\"'=");
        assertThat(AdminOrderService.cell("+1")).isEqualTo("'+1");
        assertThat(AdminOrderService.baku(LocalDateTime.of(2026, 10, 23, 20, 30))).isEqualTo("24.10.2026 00:30");
    }

    @Test
    void manualPaymentFreeAccessAndTheFigures() {
        long paidExam = database.paidExam("T-b13b manual", 5000, 3);
        Order order = seats.reserve(UUID.randomUUID(), 1_234_567, paidExam, "SANDBOX").block().order();
        assertThatThrownBy(() -> admin.markPaid(UUID.randomUUID(), order.id(), "ok", "Q-1").block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("REASON_REQUIRED"));
        assertThatThrownBy(() -> admin.markPaid(UUID.randomUUID(), order.id(), "Nağd ödəniş", " ").block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("VALIDATION_ERROR"));
        assertThat(admin.markPaid(UUID.randomUUID(), order.id(), "Nağd ödəniş kassada", "Q-2026-001").block().status()).isEqualTo(OrderStatus.PAID);
        var detail = admin.detail(order.id()).block();
        assertThat(detail.events()).singleElement().satisfies(event -> {
            assertThat(event.provider()).isEqualTo("MANUAL");
            assertThat(event.eventType()).isEqualTo("MANUAL_PAYMENT");
        });
        assertThat(detail.entitlements()).singleElement().satisfies(entitlement -> assertThat(entitlement.status()).isEqualTo("ACTIVE"));
        assertThatThrownBy(() -> admin.markPaid(UUID.randomUUID(), order.id(), "Nağd ödəniş", "Q-2").block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("ORDER_STATUS_CONFLICT"));

        var granted = admin.grant(UUID.randomUUID(), paidExam, null, 7_777_777, "Olimpiada qalibi", 2).block();
        assertThat(granted.guestId()).isEqualTo(KNOWN_GUEST);
        assertThat(granted.attemptsLeft()).isEqualTo(2);
        assertThatThrownBy(() -> admin.grant(UUID.randomUUID(), paidExam, null, 7_777_777, "Yenə", 1).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("ALREADY_ENTITLED"));
        assertThatThrownBy(() -> admin.grant(UUID.randomUUID(), paidExam, null, 1_111_111, "Naməlum", 1).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("GUEST_NOT_FOUND"));
        long audience = database.exam("T-b13b audience");
        assertThatThrownBy(() -> admin.grant(UUID.randomUUID(), audience, null, 7_777_777, "Pulsuz", 1).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("EXAM_NOT_FOUND"));

        seats.reserve(UUID.randomUUID(), 1_234_568, paidExam, "SANDBOX").block();
        PaidDtos.ExamSummary summary = admin.summary(paidExam).block();
        assertThat(summary.paidCount()).isEqualTo(1);
        assertThat(summary.revenueMinor()).isEqualTo(5000);
        assertThat(summary.pendingCount()).isEqualTo(1);
        assertThat(summary.needsReviewCount()).isZero();
        assertThat(summary.seatsTaken()).isEqualTo(2);
        assertThat(summary.seatLimit()).isEqualTo(3);
    }
}
