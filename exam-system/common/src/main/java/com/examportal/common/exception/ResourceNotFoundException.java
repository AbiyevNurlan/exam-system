package com.examportal.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Resource Not Found Exception
 * Thrown when a requested resource cannot be found.
 */
public class ResourceNotFoundException extends ApplicationException {

    public ResourceNotFoundException(String resource, String identifier) {
        super(
                String.format("%s not found with identifier: %s", resource, identifier),
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND"
        );
    }

    public ResourceNotFoundException(String message) {
        super(message, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
}
