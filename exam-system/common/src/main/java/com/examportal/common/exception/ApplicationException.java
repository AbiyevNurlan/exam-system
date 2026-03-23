package com.examportal.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base Application Exception
 * Custom exception class for application-specific errors.
 */
@Getter
public class ApplicationException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public ApplicationException(String message) {
        super(message);
        this.status = HttpStatus.INTERNAL_SERVER_ERROR;
        this.errorCode = "INTERNAL_ERROR";
    }

    public ApplicationException(String message, HttpStatus status) {
        super(message);
        this.status = status;
        this.errorCode = "ERROR";
    }

    public ApplicationException(String message, HttpStatus status, String errorCode) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public ApplicationException(String message, Throwable cause) {
        super(message, cause);
        this.status = HttpStatus.INTERNAL_SERVER_ERROR;
        this.errorCode = "INTERNAL_ERROR";
    }

    public ApplicationException(String message, HttpStatus status, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.errorCode = "ERROR";
    }
}
