package com.examportal.paid.payment;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B13b item 13 on a real PostgreSQL: while payments are notified (valid, forged, with card fields), refunded and
 * reconciled, no log line holds a signature, the body of a notification, the provider's reference, the checkout
 * link's signature or card data; the stored payload has no card field. Skipped without Docker.
 */
class PaidLogHygieneTest {

    private static PaidTestDatabase database;

    @BeforeAll
    static void start() {
        database = PaidTestDatabase.start();
    }

    @AfterAll
    static void stop() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void noSecretOrCardDataInTheLog() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(logs);
        try {
            SandboxPaymentProvider sandbox = new SandboxPaymentProvider(true, "secret-secret-secret", "");
            OrderRepository orders = new OrderRepository(database.db);
            EntitlementRepository entitlements = new EntitlementRepository(database.db);
            PaidExamRepository exams = new PaidExamRepository(database.db);
            PaymentEventRepository events = new PaymentEventRepository(database.db);
            PaymentProviders providers = new PaymentProviders(List.of(sandbox, new ManualPaymentProvider()), "SANDBOX");
            PaidNotifications notifications = new PaidNotifications();
            OrderRefunds refunds = new OrderRefunds(orders, entitlements, events, providers, database.tx, notifications);
            PaymentWebhookService webhooks = new PaymentWebhookService(providers, orders, entitlements, exams, events, refunds, notifications, database.tx);
            SeatReservation seats = new SeatReservation(exams, orders, entitlements, database.tx);

            long exam = database.paidExam("T-b13b logs", 2500, null);
            Order order = seats.reserve(UUID.randomUUID(), 1_234_567, exam, "SANDBOX").block().order();
            String providerRef = "sbx_secretref_0123456789";
            var note = sandbox.notification(PaymentProvider.EventType.PAYMENT_SUCCEEDED, order.orderNo(), 2500, "AZN", providerRef);
            String withCard = new String(note.body(), StandardCharsets.UTF_8).replace("{", "{\"pan\":\"4111111111111111\",\"cvv\":\"987\",");
            HttpHeaders forged = new HttpHeaders();
            forged.add(SandboxPaymentProvider.SIGNATURE_HEADER, "deadbeef" + "0".repeat(56));
            assertThatThrownBy(() -> webhooks.handle("SANDBOX", withCard.getBytes(StandardCharsets.UTF_8), forged).block());
            HttpHeaders signed = new HttpHeaders();
            String cardSignature = sandbox.sign(withCard.getBytes(StandardCharsets.UTF_8));
            signed.add(SandboxPaymentProvider.SIGNATURE_HEADER, cardSignature);
            webhooks.handle("SANDBOX", withCard.getBytes(StandardCharsets.UTF_8), signed).block();
            refunds.refund(order.id(), "test").block();

            for (ILoggingEvent event : logs.list) {
                String line = event.getFormattedMessage();
                assertThat(line).as(line).doesNotContain("4111111111111111", "987", cardSignature, "deadbeef", providerRef, "secret-secret",
                        sandbox.linkSignature(order.orderNo()), "\"eventId\"");
            }
            assertThat(logs.list).isNotEmpty();
            assertThat(database.scalar("SELECT count(*) FROM payment_events WHERE payload LIKE '%4111%' OR payload LIKE '%987%'")).isZero();
        } finally {
            root.detachAppender(logs);
        }
    }
}
