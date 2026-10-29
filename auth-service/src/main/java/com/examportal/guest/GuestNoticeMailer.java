package com.examportal.guest;

import com.examportal.auth.mail.EmailSender;
import com.examportal.common.utils.LogMaskUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * B13b item 12: the guest's receipt (an order is paid) and the refund notice, in Azerbaijani, through the same
 * EmailSender as every email of the system. The send is detached from the caller and tried 3 times (1 s, 3 s apart);
 * a failure is logged with the masked address only. No links, nothing loaded from outside. Times in Baku. A DEMO order
 * (paid with the sandbox, PAYMENTS_DEMO_MODE) says "DEMO — real ödəniş alınmayıb" in the subject and on top.
 */
@Slf4j
@Component
public class GuestNoticeMailer {

    private static final ZoneId BAKU = ZoneId.of("Asia/Baku");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public enum Kind { RECEIPT, REFUND }

    public static final String DEMO_HEADER = "DEMO — real ödəniş alınmayıb";

    /** What a notice says; the amount in whole minor units, the time in UTC; demo: a DEMO order (no real money) */
    public record Notice(Kind kind, String orderNo, String examTitle, int amountMinor, String currency, LocalDateTime at, Boolean demo) {

        public Notice(Kind kind, String orderNo, String examTitle, int amountMinor, String currency, LocalDateTime at) {
            this(kind, orderNo, examTitle, amountMinor, currency, at, false);
        }

        public boolean isDemo() {
            return Boolean.TRUE.equals(demo);
        }
    }

    private final EmailSender emailSender;

    public GuestNoticeMailer(EmailSender emailSender) {
        this.emailSender = emailSender;
    }

    public void send(Guest guest, Notice notice) {
        String masked = LogMaskUtils.maskEmail(guest.email());
        String subject = (notice.isDemo() ? DEMO_HEADER + " · " : "")
                + (notice.kind() == Kind.RECEIPT ? "Ödəniş qəbzi: " + notice.orderNo() : "Geri qaytarma: " + notice.orderNo());
        attempt(guest.email(), subject, text(guest, notice), html(guest, notice), 1)
                .subscribe(unused -> { }, error -> log.warn("{} email to {} was not sent", notice.kind(), masked),
                        () -> log.info("{} email to {} sent", notice.kind(), masked));
    }

    private Mono<Void> attempt(String email, String subject, String text, String html, int attempt) {
        return emailSender.send(email, subject, text, html)
                .onErrorResume(error -> attempt >= 3 ? Mono.error(error)
                        : Mono.delay(Duration.ofSeconds(attempt == 1 ? 1 : 3), Schedulers.boundedElastic())
                        .then(Mono.defer(() -> attempt(email, subject, text, html, attempt + 1))));
    }

    static String money(int minor, String currency) {
        return (minor / 100) + "," + String.format("%02d", minor % 100) + " " + currency;
    }

    static String baku(LocalDateTime utc) {
        return utc == null ? "" : utc.atZone(ZoneOffset.UTC).withZoneSameInstant(BAKU).format(TIME) + " (Bakı)";
    }

    static String text(Guest guest, Notice notice) {
        String intro = notice.kind() == Kind.RECEIPT ? "Ödənişiniz qəbul olundu. Qəbz:" : "Ödənişiniz geri qaytarıldı:";
        String demo = notice.isDemo() ? DEMO_HEADER + "\nBu sınaq ödənişidir: kartınızdan pul tutulmayıb.\n\n" : "";
        return demo + "Salam, " + guest.firstName() + "!\n\n" + intro + "\n\n"
                + "Sifariş nömrəsi: " + notice.orderNo() + "\n"
                + "İmtahan: " + notice.examTitle() + "\n"
                + "Məbləğ: " + money(notice.amountMinor(), notice.currency()) + "\n"
                + "Tarix: " + baku(notice.at()) + "\n"
                + "İştirakçı kodu: " + guest.participantCode() + "\n\n"
                + (notice.kind() == Kind.RECEIPT ? "İmtahana öz e-poçtunuz və birdəfəlik kodla daxil ola bilərsiniz.\n"
                : "Məbləğin hesabınıza çatması bankınızdan asılı olaraq bir neçə gün çəkə bilər.\n");
    }

    static String html(Guest guest, Notice notice) {
        return "<!DOCTYPE html><html lang=\"az\"><head><meta charset=\"UTF-8\"></head>"
                + "<body style=\"font-family:Arial,Helvetica,sans-serif;font-size:15px;color:#1f2933;\"><pre style=\"font-family:inherit;white-space:pre-wrap\">"
                + escape(text(guest, notice)) + "</pre></body></html>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
