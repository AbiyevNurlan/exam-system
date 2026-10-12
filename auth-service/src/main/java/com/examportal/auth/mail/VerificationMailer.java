package com.examportal.auth.mail;

import com.examportal.common.utils.LogMaskUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Verification Mailer
 * Builds and sends the emails that carry a one-time code (email verification, password reset).
 *
 * The send is detached from the request on purpose: a slow or failing mail server must not delay
 * or break registration, and the response time must not tell a caller whether an email was sent.
 * A failure is only logged (masked address, exception type, never the code); the user asks for a
 * new code.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationMailer {

    private static final String SUBJECT = "Your verification code";
    private static final String RESET_SUBJECT = "Your password reset code";

    private final EmailSender emailSender;

    public void sendVerificationCode(String email, String code, int validMinutes) {
        send("Verification", email, SUBJECT, body(code, validMinutes));
    }

    public void sendPasswordResetCode(String email, String code, int validMinutes) {
        send("Password reset", email, RESET_SUBJECT, resetBody(code, validMinutes));
    }

    private void send(String kind, String email, String subject, String body) {
        emailSender.send(email, subject, body)
                .subscribe(
                        unused -> { },
                        error -> log.warn("{} email could not be sent to {}: {}", kind,
                                LogMaskUtils.maskEmail(email), error.getClass().getSimpleName()),
                        () -> log.info("{} email sent to {}", kind, LogMaskUtils.maskEmail(email)));
    }

    private String resetBody(String code, int validMinutes) {
        return "Your password reset code is: " + code + "\n\n"
                + "It is valid for " + validMinutes + (validMinutes == 1 ? " minute" : " minutes") + " and can be used once.\n\n"
                + "If you did not ask to reset your password, you can ignore this email; your password stays unchanged.\n";
    }

    /**
     * Plain text, no links: nothing in it can be turned into a phishing target
     */
    private String body(String code, int validMinutes) {
        return "Your verification code is: " + code + "\n\n"
                + "It is valid for " + validMinutes + (validMinutes == 1 ? " minute" : " minutes") + " and can be used once.\n\n"
                + "If you did not create an account, you can ignore this email.\n";
    }
}