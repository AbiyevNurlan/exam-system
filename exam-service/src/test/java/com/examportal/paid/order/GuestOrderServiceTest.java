package com.examportal.paid.order;

import com.examportal.common.exception.ApplicationException;
import com.examportal.paid.PaidTestDatabase;
import com.examportal.paid.exam.PaidExamRepository;
import com.examportal.paid.guest.GuestDirectory;
import com.examportal.paid.provider.ManualPaymentProvider;
import com.examportal.paid.provider.PaymentProvider;
import com.examportal.paid.provider.PaymentProviders;
import com.examportal.paid.provider.SandboxPaymentProvider;
import com.examportal.paid.security.PaidGuest;
import com.examportal.paid.web.PaidDtos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B13b item 5 on a real PostgreSQL: an order with its number, the amount in whole minor units and the sandbox
 * checkout; the same order again (idempotent); only the own orders (another guest's is 404); a blocked guest; no
 * order without an available provider; a provider that cannot make the checkout fails the order and gives the seat
 * back. Skipped without Docker.
 */
class GuestOrderServiceTest {

    private static PaidTestDatabase database;
    private static final GuestDirectory directory = mock(GuestDirectory.class);

    @BeforeAll
    static void start() {
        database = PaidTestDatabase.start();
        when(directory.active(any())).thenAnswer(call -> {
            UUID id = call.getArgument(0);
            return id.getMostSignificantBits() == 0 ? Mono.error(com.examportal.paid.PaidErrors.guestBlocked())
                    : Mono.just(new GuestDirectory.GuestRef(id, 4_141_592, "guest@x.test", "Aysel", "Quliyeva", false));
        });
    }

    @AfterAll
    static void stop() {
        if (database != null) {
            database.close();
        }
    }

    private static GuestOrderService service(PaymentProvider provider, String checkoutProvider) {
        OrderRepository orders = new OrderRepository(database.db);
        PaidExamRepository exams = new PaidExamRepository(database.db);
        return new GuestOrderService(directory, new PaymentProviders(List.of(provider, new ManualPaymentProvider()), checkoutProvider),
                new SeatReservation(exams, orders, new EntitlementRepository(database.db), database.tx), orders, exams, database.tx,
                new com.examportal.paid.payment.PaidNotifications());
    }

    private static String code(Throwable error) {
        return ((ApplicationException) error).getErrorCode();
    }

    @Test
    void anOrderItsCheckoutAndTheSameOrderAgain() {
        GuestOrderService service = service(new SandboxPaymentProvider(true, "secret-secret-secret", ""), "SANDBOX");
        long exam = database.paidExam("T-b13b order", 2550, 10);
        PaidGuest guest = new PaidGuest(UUID.randomUUID(), 4_141_592);

        PaidDtos.OrderView first = service.create(guest, exam).block();
        assertThat(first.orderNo()).matches("YK-\\d{6}-\\d{6}");
        assertThat(first.amountMinor()).isEqualTo(2550);
        assertThat(first.currency()).isEqualTo("AZN");
        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(first.examTitle()).isEqualTo("T-b13b order");
        assertThat(first.checkoutUrl()).startsWith("/api/payments/sandbox/checkout/" + first.orderNo() + "?sig=");
        assertThat(database.scalar("SELECT count(*) FROM orders WHERE provider_ref LIKE 'sbx_%' AND id = " + first.id())).isEqualTo(1);

        PaidDtos.OrderView again = service.create(guest, exam).block();
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.checkoutUrl()).isEqualTo(first.checkoutUrl());
        assertThat(database.scalar("SELECT seats_taken FROM exams WHERE id = " + exam)).isEqualTo(1);

        assertThat(service.list(guest).block()).extracting(PaidDtos.OrderView::id).containsExactly(first.id());
        assertThat(service.get(guest, first.id()).block().checkoutUrl()).isNotNull();
        PaidGuest other = new PaidGuest(UUID.randomUUID(), 1_234_567);
        assertThatThrownBy(() -> service.get(other, first.id()).block()).satisfies(error -> assertThat(code(error)).isEqualTo("ORDER_NOT_FOUND"));
        assertThat(service.list(other).block()).isEmpty();
    }

    @Test
    void blockedGuestsAndMissingProvidersMakeNoOrder() {
        long exam = database.paidExam("T-b13b refused", 1000, null);
        GuestOrderService sandbox = service(new SandboxPaymentProvider(true, "secret-secret-secret", ""), "SANDBOX");
        assertThatThrownBy(() -> sandbox.create(new PaidGuest(new UUID(0, 7), 1_234_567), exam).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("GUEST_BLOCKED"));
        GuestOrderService closed = service(new SandboxPaymentProvider(false, "secret-secret-secret", ""), "SANDBOX");
        assertThatThrownBy(() -> closed.create(new PaidGuest(UUID.randomUUID(), 1_234_567), exam).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("PAYMENT_PROVIDER_UNAVAILABLE"));
        assertThat(database.scalar("SELECT count(*) FROM orders WHERE exam_id = " + exam)).isZero();
    }

    @Test
    void aFailedCheckoutFailsTheOrderAndGivesTheSeatBack() {
        PaymentProvider broken = mock(PaymentProvider.class);
        when(broken.name()).thenReturn("SANDBOX");
        when(broken.available()).thenReturn(true);
        when(broken.createCheckout(any())).thenReturn(Mono.error(new IllegalStateException("provider down")));
        when(broken.verifyWebhook(any(), any(HttpHeaders.class))).thenReturn(PaymentProvider.Verification.invalid(null));
        long exam = database.paidExam("T-b13b broken provider", 1000, 1);
        assertThatThrownBy(() -> service(broken, "SANDBOX").create(new PaidGuest(UUID.randomUUID(), 1_234_567), exam).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("PAYMENT_PROVIDER_ERROR"));
        assertThat(database.scalar("SELECT count(*) FROM orders WHERE exam_id = " + exam + " AND status = 'FAILED' AND NOT seat_held")).isEqualTo(1);
        assertThat(database.scalar("SELECT seats_taken FROM exams WHERE id = " + exam)).isZero();
    }

    @Test
    void anOrderNamesTheWindowOfItsExam() {
        GuestOrderService service = service(new SandboxPaymentProvider(true, "secret-secret-secret", ""), "SANDBOX");
        long exam = database.paidExam("T-b13b window", 1500, null);
        java.time.LocalDateTime[] window = database.db.sql("SELECT start_time, end_time FROM exams WHERE id = :id").bind("id", exam)
                .map(row -> new java.time.LocalDateTime[] {row.get("start_time", java.time.LocalDateTime.class), row.get("end_time", java.time.LocalDateTime.class)})
                .one().block();
        PaidGuest guest = new PaidGuest(UUID.randomUUID(), 4_141_592);

        PaidDtos.OrderView created = service.create(guest, exam).block();
        for (PaidDtos.OrderView view : List.of(created, service.get(guest, created.id()).block(), service.list(guest).block().get(0))) {
            assertThat(view.examStartsAt()).isEqualTo(window[0]);
            assertThat(view.examEndsAt()).isEqualTo(window[1]);
        }
        // Another guest still gets nothing of it
        assertThatThrownBy(() -> service.get(new PaidGuest(UUID.randomUUID(), 1_234_567), created.id()).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("ORDER_NOT_FOUND"));
    }
}
