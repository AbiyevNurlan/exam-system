package com.examportal.guest;

import com.examportal.auth.mail.EmailSender;
import com.examportal.guest.web.GuestController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * PAYMENTS_DEMO_MODE in auth-service: GET /api/guest/feature says paymentsDemo exactly when the paid flow pays with the
 * sandbox (the rule of exam-service, PaymentsMode); a DEMO order's receipt and refund notice say "DEMO — real ödəniş
 * alınmayıb" in the subject and on top, a real one as before.
 */
class PaymentsDemoFeatureTest {

    private static final Guest GUEST = new Guest(UUID.randomUUID(), "aysel@x.test", LocalDateTime.now(), "Aysel", "Quliyeva", "+994501234567",
            4_141_592, LocalDateTime.now(), "v1", LocalDateTime.now(), null, false, null);

    private static GuestFeature feature(String profile, boolean paid, String provider, boolean allowed, boolean demo, String override) {
        MockEnvironment environment = new MockEnvironment();
        if (profile != null) {
            environment.setActiveProfiles(profile);
        }
        return new GuestFeature(environment, paid, provider, allowed, demo, override);
    }

    private static boolean answer(GuestFeature feature, boolean paid) {
        var data = new GuestController(mock(GuestService.class), feature).feature().block().getBody().getData();
        assertThat(data.paidAccessEnabled()).isEqualTo(paid);
        return data.paymentsDemo();
    }

    @Test
    void theFeatureSaysWhetherPaymentsAreADemo() {
        assertThat(answer(feature("production", true, "SANDBOX", true, true, null), true)).isTrue();
        // Production without the demo, or a switch missing: no demo (the sandbox stays closed)
        assertThat(answer(feature("production", true, "SANDBOX", true, false, null), true)).isFalse();
        assertThat(answer(feature("production", true, "SANDBOX", false, true, null), true)).isFalse();
        assertThat(answer(feature("production", true, "EXTERNAL", true, true, null), true)).isFalse();
        // The paid exams off: nothing is paid at all
        assertThat(answer(feature("production", false, "SANDBOX", true, true, null), false)).isFalse();
        // The older override
        assertThat(answer(feature("production", true, "SANDBOX", true, false, "I-UNDERSTAND"), true)).isTrue();
        // Development: the sandbox switch decides, as there
        assertThat(answer(feature("docker", true, "SANDBOX", true, false, null), true)).isTrue();
        assertThat(answer(feature(null, true, "SANDBOX", false, false, null), true)).isFalse();
        // The constructor of before: no demo
        assertThat(answer(new GuestFeature(true), true)).isFalse();
    }

    record Sent(String subject, String text) {
    }

    private static Sent send(GuestNoticeMailer.Notice notice) {
        List<Sent> sent = new ArrayList<>();
        EmailSender sender = (to, subject, text, html) -> Mono.fromRunnable(() -> sent.add(new Sent(subject, text)));
        new GuestNoticeMailer(sender).send(GUEST, notice);
        assertThat(sent).hasSize(1);
        return sent.get(0);
    }

    @Test
    void aDemoReceiptSaysSoAndARealOneIsAsBefore() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 29, 9, 0);
        Sent demo = send(new GuestNoticeMailer.Notice(GuestNoticeMailer.Kind.RECEIPT, "YK-261029-000001", "Kimya", 3000, "AZN", at, true));
        assertThat(demo.subject()).isEqualTo("DEMO — real ödəniş alınmayıb · Ödəniş qəbzi: YK-261029-000001");
        assertThat(demo.text()).startsWith("DEMO — real ödəniş alınmayıb\n").contains("kartınızdan pul tutulmayıb", "Məbləğ: 30,00 AZN");

        Sent refund = send(new GuestNoticeMailer.Notice(GuestNoticeMailer.Kind.REFUND, "YK-261029-000001", "Kimya", 3000, "AZN", at, true));
        assertThat(refund.subject()).startsWith("DEMO — real ödəniş alınmayıb · Geri qaytarma:");

        // As exam-service sends it (with demo), and as an exam-service of before did (without)
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        try {
            assertThat(json.readValue("{\"kind\":\"RECEIPT\",\"orderNo\":\"YK-1\",\"examTitle\":\"K\",\"amountMinor\":100,\"currency\":\"AZN\","
                    + "\"at\":\"2026-10-29T09:00:00\",\"demo\":true}", GuestNoticeMailer.Notice.class).isDemo()).isTrue();
            assertThat(json.readValue("{\"kind\":\"RECEIPT\",\"orderNo\":\"YK-1\",\"examTitle\":\"K\",\"amountMinor\":100,\"currency\":\"AZN\","
                    + "\"at\":\"2026-10-29T09:00:00\"}", GuestNoticeMailer.Notice.class).isDemo()).isFalse();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError(e);
        }

        for (Boolean real : new Boolean[]{false, null}) {
            Sent receipt = send(new GuestNoticeMailer.Notice(GuestNoticeMailer.Kind.RECEIPT, "YK-261029-000002", "Kimya", 3000, "AZN", at, real));
            assertThat(receipt.subject()).isEqualTo("Ödəniş qəbzi: YK-261029-000002");
            assertThat(receipt.text()).startsWith("Salam, Aysel!").doesNotContain("DEMO");
        }
    }
}
