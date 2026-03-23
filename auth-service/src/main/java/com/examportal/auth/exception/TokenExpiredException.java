package com.examportal.auth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Exception thrown when a token has expired
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class TokenExpiredException extends RuntimeException {

    public TokenExpiredException() {
        super("Token has expired");
    }

    public TokenExpiredException(String message) {
        super(message);
    }

    public TokenExpiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
