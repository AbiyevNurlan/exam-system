package com.examportal.auth.exception;

import com.examportal.common.exception.ApplicationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Exception thrown when a token has expired
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class TokenExpiredException extends ApplicationException {

    private static final String ERROR_CODE = "TOKEN_EXPIRED";

    public TokenExpiredException() {
        super("Token has expired", HttpStatus.UNAUTHORIZED, ERROR_CODE);
    }

    public TokenExpiredException(String message) {
        super(message, HttpStatus.UNAUTHORIZED, ERROR_CODE);
    }

    public TokenExpiredException(String message, Throwable cause) {
        super(message, HttpStatus.UNAUTHORIZED, ERROR_CODE, cause);
    }
}
