package com.examportal.auth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Exception thrown when a user tries to register with an email that already exists
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException(String email) {
        super("User with email '" + email + "' already exists");
    }

    public DuplicateEmailException(String message, Throwable cause) {
        super(message, cause);
    }
}
