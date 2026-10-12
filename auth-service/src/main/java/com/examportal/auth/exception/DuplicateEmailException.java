package com.examportal.auth.exception;

import com.examportal.common.exception.ApplicationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Exception thrown when a user tries to register with an email that already exists
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateEmailException extends ApplicationException {

    public DuplicateEmailException(String email) {
        super("User with email '" + email + "' already exists", HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS");
    }

    public DuplicateEmailException(String message, Throwable cause) {
        super(message, HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", cause);
    }
}
