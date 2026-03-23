package com.examportal.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Authentication Exception
 * Thrown when authentication fails.
 */
public class AuthenticationException extends ApplicationException {

    public AuthenticationException(String message) {
        super(message, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_ERROR");
    }

    public static AuthenticationException invalidCredentials() {
        return new AuthenticationException("Invalid email or password");
    }

    public static AuthenticationException tokenExpired() {
        return new AuthenticationException("Token has expired");
    }

    public static AuthenticationException invalidToken() {
        return new AuthenticationException("Invalid authentication token");
    }

    public static AuthenticationException accountLocked() {
        return new AuthenticationException("Account is locked");
    }
}
