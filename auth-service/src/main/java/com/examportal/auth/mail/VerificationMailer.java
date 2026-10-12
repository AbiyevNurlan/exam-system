package com.examportal.auth.mail;

import com.examportal.common.utils.LogMaskUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Verification Mailer
 * Builds and sends the email that carries a verification code.
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

    private final EmailSender emailSender;

    public void sendVerificationCode(String email, String code, int validMinutes) {
        emailSender.send(email, SUBJECT, body(code, validMinutes))
                .subscribe(
                        unused -> { },
                        error -> log.warn("Verification email could not be sent to {}: {}",
                                LogMaskUtils.maskEmail(email), error.getClass().getSimpleName()),
                        () -> log.info("Verification email sent to {}", LogMaskUtils.maskEmail(email)));
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