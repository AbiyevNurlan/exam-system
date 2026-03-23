package com.examportal.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Access Denied Exception
 * Thrown when a user attempts to access a resource they don't have permission for.
 */
public class AccessDeniedException extends ApplicationException {

    public AccessDeniedException(String message) {
        super(message, HttpStatus.FORBIDDEN, "ACCESS_DENIED");
    }

    public static AccessDeniedException insufficientPermissions() {
        return new AccessDeniedException("Insufficient permissions to perform this action");
    }

    public static AccessDeniedException resourceOwnershipRequired() {
        return new AccessDeniedException("You don't have ownership of this resource");
    }
}
