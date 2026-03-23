package com.examportal.auth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Exception thrown when login credentials are invalid
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }

    public InvalidCredentialsException(String message) {
        super(message);
    }

    public InvalidCredentialsException(String message, Throwable cause) {
        super(message, cause);
    }
}
