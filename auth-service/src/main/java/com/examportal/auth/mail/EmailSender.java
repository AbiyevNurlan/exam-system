package com.examportal.auth.mail;

import reactor.core.publisher.Mono;

/**
 * Email Sender
 * The only way this service sends email. Implementations must not block the calling thread and
 * must not log the body (it can contain a one-time code) or the full recipient address.
 */
public interface EmailSender {

    /**
     * Send one email with a plain-text body and an HTML alternative of the same content, once
     * (retrying is the caller's decision). Completes when the mail server accepted it, errors otherwise.
     */
    Mono<Void> send(String to, String subject, String text, String html);
}
