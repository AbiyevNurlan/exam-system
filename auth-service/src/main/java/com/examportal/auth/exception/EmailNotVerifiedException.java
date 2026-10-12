package com.examportal.auth.exception;

import com.examportal.common.exception.ApplicationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Exception thrown when a user with correct credentials has not verified the email address yet.
 * Raised only after the password was accepted, so it tells nothing to someone who does not know it.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class EmailNotVerifiedException extends ApplicationException {

    public EmailNotVerifiedException() {
        super("Email address is not verified", HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED");
    }
}