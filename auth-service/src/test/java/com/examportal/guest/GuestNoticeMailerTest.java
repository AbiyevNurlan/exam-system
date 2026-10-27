package com.examportal.guest;

import com.examportal.auth.mail.EmailSender;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B13b item 12: the receipt and the refund notice in Azerbaijani, with the order number, the exam, the amount from whole
 * minor units, the time in Baku and the participant code; no link; a failing mail server is tried again.
 */
class GuestNoticeMailerTest {

    private static final Guest GUEST = new Guest(UUID.randomUUID(), "aysel@x.test", LocalDateTime.now(), "Aysel", "Quliyeva", "+994501234567",
            4_141_592, LocalDateTime.now(), "v1", LocalDateTime.now(), null, false, null);

    record Sent(String to, String subject, String text, String html) {
    }

    @Test
    void theReceiptSaysEverythingInAzerbaijani() {
        List<Sent> sent = new ArrayList<>();
        EmailSender sender = (to, subject, text, html) -> Mono.fromRunnable(() -> sent.add(new Sent(to, subject, text, html)));
        new GuestNoticeMailer(sender).send(GUEST, new GuestNoticeMailer.Notice(GuestNoticeMailer.Kind.RECEIPT, "YK-261023-000042",
                "Riyaziyyat <olimpiada>", 2505, "AZN", LocalDateTime.of(2026, 10, 23, 20, 30)));
        assertThat(sent).singleElement().satisfies(mail -> {
            assertThat(mail.to()).isEqualTo("aysel@x.test");
            assertThat(mail.subject()).isEqualTo("Ödəniş qəbzi: YK-261023-000042");
            assertThat(mail.text()).contains("Salam, Aysel!", "Sifariş nömrəsi: YK-261023-000042", "İmtahan: Riyaziyyat <olimpiada>",
                    "Məbləğ: 25,05 AZN", "Tarix: 24.10.2026 00:30 (Bakı)", "İştirakçı kodu: 4141592");
            assertThat(mail.html()).contains("&lt;olimpiada&gt;").doesNotContain("<a ", "http");
        });
    }

    @Test
    void theRefundNoticeAndRetries() {
        List<Sent> sent = new ArrayList<>();
        int[] calls = {0};
        EmailSender flaky = (to, subject, text, html) -> Mono.defer(() -> ++calls[0] == 1
                ? Mono.error(new IllegalStateException("smtp down"))
                : Mono.fromRunnable(() -> sent.add(new Sent(to, subject, text, html))));
        new GuestNoticeMailer(flaky).send(GUEST, new GuestNoticeMailer.Notice(GuestNoticeMailer.Kind.REFUND, "YK-1", "Fizika", 1000, "AZN",
                LocalDateTime.of(2026, 10, 23, 8, 0)));
        long deadline = System.currentTimeMillis() + 5000;
        while (sent.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(calls[0]).isEqualTo(2);
        assertThat(sent).singleElement().satisfies(mail -> {
            assertThat(mail.subject()).isEqualTo("Geri qaytarma: YK-1");
            assertThat(mail.text()).contains("geri qaytarıldı", "Məbləğ: 10,00 AZN", "Tarix: 23.10.2026 12:00 (Bakı)");
        });
    }
}
