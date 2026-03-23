package com.examportal.auth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Exception thrown when a refresh token is invalid
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException() {
        super("Invalid or revoked token");
    }

    public InvalidTokenException(String message) {
        super(message);
    }

    public InvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
