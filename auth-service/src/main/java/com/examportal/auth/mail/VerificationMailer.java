package com.examportal.auth.mail;

import com.examportal.common.utils.LogMaskUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

/**
 * Verification Mailer
 * Builds and sends the emails that carry a one-time code (email verification, password reset).
 *
 * The send is detached from the request on purpose: a slow or failing mail server must not delay
 * or break registration, and the response time must not tell a caller whether an email was sent.
 * A failed send is tried again (3 attempts, 1 s and 3 s apart); after the last one the failure is
 * only logged and counted, and the user asks for a new code. Log lines carry the masked address,
 * the attempt and the exception types, never the code.
 */
@Slf4j
@Component
public class VerificationMailer {

    private static final int MAX_ATTEMPTS = 3;
    // Pause before attempt 2 and before attempt 3
    private static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(1), Duration.ofSeconds(3));

    /**
     * One template for every email; only the wording differs
     */
    private enum Kind {
        VERIFICATION("Verification",
                "Təsdiq kodunuz",
                "Qeydiyyatı tamamlamaq üçün təsdiq kodunuz:",
                "Əgər bu sorğunu siz göndərməmisinizsə, bu məktubu nəzərə almayın."),
        PASSWORD_RESET("Password reset",
                "Şifrə bərpa kodunuz",
                "Şifrənizi yeniləmək üçün kodunuz:",
                "Əgər bu sorğunu siz göndərməmisinizsə, bu məktubu nəzərə almayın; şifrəniz dəyişməyəcək."),
        // B13a: the sign-in code of a guest participant of paid open exams (no password)
        GUEST_LOGIN("Guest sign-in",
                "Giriş kodunuz",
                "İmtahan sisteminə daxil olmaq üçün kodunuz:",
                "Əgər bu sorğunu siz göndərməmisinizsə, bu məktubu nəzərə almayın.");

        private final String logName;
        private final String subject;
        private final String intro;
        private final String ignoreNote;

        Kind(String logName, String subject, String intro, String ignoreNote) {
            this.logName = logName;
            this.subject = subject;
            this.intro = intro;
            this.ignoreNote = ignoreNote;
        }
    }

    private final EmailSender emailSender;
    // mail_sent_total / mail_failed_total: one count per email, not per attempt
    private final Counter sent;
    private final Counter failed;

    public VerificationMailer(EmailSender emailSender, MeterRegistry meterRegistry) {
        this.emailSender = emailSender;
        this.sent = Counter.builder("mail.sent")
                .description("Emails accepted by the mail server")
                .register(meterRegistry);
        this.failed = Counter.builder("mail.failed")
                .description("Emails given up after the last attempt")
                .register(meterRegistry);
    }

    public void sendVerificationCode(String email, String code, int validMinutes) {
        send(Kind.VERIFICATION, email, code, validMinutes);
    }

    public void sendPasswordResetCode(String email, String code, int validMinutes) {
        send(Kind.PASSWORD_RESET, email, code, validMinutes);
    }

    public void sendGuestLoginCode(String email, String code, int validMinutes) {
        send(Kind.GUEST_LOGIN, email, code, validMinutes);
    }

    private void send(Kind kind, String email, String code, int validMinutes) {
        String masked = LogMaskUtils.maskEmail(email);
        attempt(kind, email, masked, textBody(kind, code, validMinutes), htmlBody(kind, code, validMinutes), 1)
                .subscribe(
                        unused -> { },
                        error -> {
                            failed.increment();
                            log.warn("{} email to {} was not sent: gave up after {} attempts",
                                    kind.logName, masked, MAX_ATTEMPTS);
                        },
                        sent::increment);
    }

    private Mono<Void> attempt(Kind kind, String email, String masked, String text, String html, int attempt) {
        return emailSender.send(email, kind.subject, text, html)
                .doOnSuccess(unused -> log.info("{} email to {}: attempt {}/{} succeeded",
                        kind.logName, masked, attempt, MAX_ATTEMPTS))
                .onErrorResume(error -> {
                    // Types only: an SMTP error message can quote the recipient address
                    log.warn("{} email to {}: attempt {}/{} failed ({}, cause {})",
                            kind.logName, masked, attempt, MAX_ATTEMPTS, error.getClass().getSimpleName(),
                            NestedExceptionUtils.getMostSpecificCause(error).getClass().getSimpleName());
                    if (attempt >= MAX_ATTEMPTS) {
                        return Mono.error(error);
                    }
                    return Mono.delay(BACKOFF.get(attempt - 1), Schedulers.boundedElastic())
                            .then(Mono.defer(() -> attempt(kind, email, masked, text, html, attempt + 1)));
                });
    }

    private static String validity(int validMinutes) {
        return "Kod " + validMinutes + " dəqiqə ərzində etibarlıdır və yalnız bir dəfə istifadə oluna bilər.";
    }

    /**
     * No links in either body: nothing in the email can be turned into a phishing target
     */
    private static String textBody(Kind kind, String code, int validMinutes) {
        return "Salam,\n\n"
                + kind.intro + "\n\n"
                + "    " + code + "\n\n"
                + validity(validMinutes) + "\n\n"
                + kind.ignoreNote + "\n";
    }

    /**
     * The same content as the text body. Inline styles only: no links, images or anything loaded
     * from outside, so nothing can track the reader.
     */
    private static String htmlBody(Kind kind, String code, int validMinutes) {
        return """
                <!DOCTYPE html>
                <html lang="az">
                <head><meta charset="UTF-8"><title>%s</title></head>
                <body style="margin:0;padding:24px;font-family:Arial,Helvetica,sans-serif;font-size:15px;color:#1f2933;">
                <p>Salam,</p>
                <p>%s</p>
                <p style="margin:24px 0;font-size:32px;font-weight:bold;letter-spacing:6px;">%s</p>
                <p>%s</p>
                <p style="font-size:13px;color:#616e7c;">%s</p>
                </body>
                </html>
                """.formatted(kind.subject, kind.intro, code, validity(validMinutes), kind.ignoreNote);
    }
}
