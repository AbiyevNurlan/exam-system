package com.examportal.auth.exception;

import com.examportal.common.exception.ApplicationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Exception thrown when a refresh token is invalid
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class InvalidTokenException extends ApplicationException {

    private static final String ERROR_CODE = "INVALID_TOKEN";

    public InvalidTokenException() {
        super("Invalid or revoked token", HttpStatus.UNAUTHORIZED, ERROR_CODE);
    }

    public InvalidTokenException(String message) {
        super(message, HttpStatus.UNAUTHORIZED, ERROR_CODE);
    }

    public InvalidTokenException(String message, Throwable cause) {
        super(message, HttpStatus.UNAUTHORIZED, ERROR_CODE, cause);
    }
}
