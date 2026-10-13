package com.examportal.auth.mail;

import jakarta.annotation.PostConstruct;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mail Settings
 * The sender of every email, and the startup check of the SMTP settings (MAIL_*).
 *
 * Without MAIL_HOST the service starts and every send fails cleanly. With the local Mailpit
 * nothing else is needed. Any other host is a real mail server: a wrong combination must stop
 * the start with a clear message instead of failing later, one unsent code at a time.
 */
@Component
public class MailSettings {

    private static final String MAILPIT_FROM = "no-reply@exam.localhost";
    private static final String DEFAULT_FROM_NAME = "Exam System";
    private static final int MAILPIT_PORT = 1025;

    @Value("${spring.mail.host:}")
    private String host;

    @Value("${spring.mail.port:587}")
    private int port;

    @Value("${spring.mail.username:}")
    private String username;

    @Value("${spring.mail.password:}")
    private String password;

    @Value("${spring.mail.properties.mail.smtp.auth:false}")
    private boolean smtpAuth;

    @Value("${spring.mail.properties.mail.smtp.starttls.enable:false}")
    private boolean startTls;

    @Value("${spring.mail.properties.mail.smtp.ssl.enable:false}")
    private boolean ssl;

    @Getter
    @Value("${auth.mail.from:}")
    private String from;

    @Getter
    @Value("${auth.mail.from-name:}")
    private String fromName;

    // Optional: where answers to an email go (the sender address is usually a no-reply one)
    @Getter
    @Value("${auth.mail.reply-to:}")
    private String replyTo;

    @PostConstruct
    void validate() {
        host = host == null ? "" : host.trim();
        from = from == null ? "" : from.trim();
        fromName = fromName == null || fromName.isBlank() ? DEFAULT_FROM_NAME : fromName.trim();
        replyTo = replyTo == null ? "" : replyTo.trim();
        if (host.isEmpty()) {
            return;
        }
        if (!replyTo.isEmpty()) {
            try {
                new InternetAddress(replyTo, true);
            } catch (AddressException e) {
                throw new IllegalStateException("MAIL_REPLY_TO is not a valid email address.");
            }
        }
        if (isMailpit()) {
            if (from.isEmpty()) {
                from = MAILPIT_FROM;
            }
            return;
        }
        if (from.isEmpty()) {
            throw new IllegalStateException(
                    "MAIL_FROM is not set. MAIL_HOST points to a real mail server (" + host + "), which needs "
                            + "a sender address of a domain you may send from (e.g. no-reply@your-domain).");
        }
        if (startTls && ssl) {
            throw new IllegalStateException(
                    "MAIL_STARTTLS and MAIL_SSL are both true. Use MAIL_STARTTLS=true with port 587 "
                            + "or MAIL_SSL=true with port 465, not both.");
        }
        if (smtpAuth && (isBlank(username) || isBlank(password))) {
            throw new IllegalStateException(
                    "MAIL_SMTP_AUTH is true but MAIL_USERNAME or MAIL_PASSWORD is not set.");
        }
    }

    /**
     * False when no mail server is set; a send then fails without a connection attempt
     */
    public boolean isConfigured() {
        return !host.isEmpty() && !from.isEmpty();
    }

    /**
     * The development mailbox of docker-compose.yml (service "mailpit"), or one on this machine
     */
    private boolean isMailpit() {
        return host.equalsIgnoreCase("mailpit")
                || (port == MAILPIT_PORT && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
