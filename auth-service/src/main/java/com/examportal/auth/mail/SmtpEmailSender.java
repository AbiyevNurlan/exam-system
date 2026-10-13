package com.examportal.auth.mail;

import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * SMTP Email Sender
 * Plain SMTP through Spring's JavaMailSender (the MAIL_* settings, checked by MailSettings), so any
 * provider with an SMTP endpoint works; there is no provider-specific API.
 * JavaMail blocks, so every send runs on the bounded elastic scheduler, never on a reactive thread.
 */
@Component
public class SmtpEmailSender implements EmailSender {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String CHARSET = StandardCharsets.UTF_8.name();

    private final JavaMailSender mailSender;
    private final MailSettings settings;

    public SmtpEmailSender(ObjectProvider<JavaMailSender> mailSender,
                           MailSettings settings) {
        // Absent when MAIL_HOST is not set: the service still starts, and every send fails cleanly
        this.mailSender = mailSender.getIfAvailable();
        this.settings = settings;
    }

    @Override
    public Mono<Void> send(String to, String subject, String text, String html) {
        if (mailSender == null || !settings.isConfigured()) {
            return Mono.error(new IllegalStateException("SMTP is not configured (MAIL_HOST / MAIL_FROM)"));
        }
        return Mono.<Void>fromRunnable(() -> mailSender.send(message -> {
                    message.setFrom(new InternetAddress(settings.getFrom(), settings.getFromName(), CHARSET));
                    message.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
                    if (!settings.getReplyTo().isEmpty()) {
                        message.setReplyTo(new InternetAddress[]{new InternetAddress(settings.getReplyTo())});
                    }
                    message.setSubject(subject, CHARSET);

                    // multipart/alternative: the plain text first, the HTML last (clients show the last they can)
                    MimeBodyPart plain = new MimeBodyPart();
                    plain.setText(text, CHARSET);
                    MimeBodyPart rich = new MimeBodyPart();
                    rich.setText(html, CHARSET, "html");
                    MimeMultipart body = new MimeMultipart("alternative");
                    body.addBodyPart(plain);
                    body.addBodyPart(rich);
                    message.setContent(body);
                }))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(TIMEOUT);
    }
}
