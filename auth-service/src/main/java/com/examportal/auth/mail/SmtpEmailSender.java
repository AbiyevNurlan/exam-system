package com.examportal.auth.mail;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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

    private final JavaMailSender mailSender;
    private final MailSettings settings;

    public SmtpEmailSender(ObjectProvider<JavaMailSender> mailSender,
                           MailSettings settings) {
        // Absent when MAIL_HOST is not set: the service still starts, and every send fails cleanly
        this.mailSender = mailSender.getIfAvailable();
        this.settings = settings;
    }

    @Override
    public Mono<Void> send(String to, String subject, String text) {
        if (mailSender == null || !settings.isConfigured()) {
            return Mono.error(new IllegalStateException("SMTP is not configured (MAIL_HOST / MAIL_FROM)"));
        }
        return Mono.<Void>fromRunnable(() -> {
                    SimpleMailMessage message = new SimpleMailMessage();
                    message.setFrom(settings.getFrom());
                    message.setTo(to);
                    message.setSubject(subject);
                    message.setText(text);
                    mailSender.send(message);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(TIMEOUT);
    }
}