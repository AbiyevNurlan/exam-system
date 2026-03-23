package com.examportal.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Validation Exception
 * Thrown when input validation fails.
 */
public class ValidationException extends ApplicationException {

    public ValidationException(String message) {
        super(message, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
    }

    public ValidationException(String field, String message) {
        super(
                String.format("Validation failed for field '%s': %s", field, message),
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR"
        );
    }
}
