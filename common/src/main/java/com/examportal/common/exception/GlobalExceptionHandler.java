package com.examportal.common.exception;

import com.examportal.common.domain.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Global Exception Handler
 * Centralized exception handling for all REST controllers.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApplicationException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleApplicationException(
            ApplicationException ex, ServerWebExchange exchange) {
        
        // The message can carry user data (e.g. an email), so only the code, status and path are logged
        log.error("Application error: {} ({}) - Path: {}",
                ex.getErrorCode(), ex.getStatus().value(), exchange.getRequest().getPath());
        
        ApiResponse<Void> response = ApiResponse.error(
                ex.getMessage(),
                ApiResponse.ErrorDetails.builder()
                        .code(ex.getErrorCode())
                        .description(ex.getMessage())
                        .build()
        );
        response.setPath(exchange.getRequest().getPath().value());
        
        return Mono.just(ResponseEntity.status(ex.getStatus()).body(response));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleResourceNotFoundException(
            ResourceNotFoundException ex, ServerWebExchange exchange) {
        
        log.warn("Resource not found: {} - Path: {}", ex.getMessage(), exchange.getRequest().getPath());
        
        ApiResponse<Void> response = ApiResponse.error(
                ex.getMessage(),
                ApiResponse.ErrorDetails.builder()
                        .code("RESOURCE_NOT_FOUND")
                        .description(ex.getMessage())
                        .build()
        );
        response.setPath(exchange.getRequest().getPath().value());
        
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body(response));
    }

    @ExceptionHandler(ValidationException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleValidationException(
            ValidationException ex, ServerWebExchange exchange) {
        
        log.warn("Validation error: {} - Path: {}", ex.getMessage(), exchange.getRequest().getPath());
        
        ApiResponse<Void> response = ApiResponse.error(
                ex.getMessage(),
                ApiResponse.ErrorDetails.builder()
                        .code("VALIDATION_ERROR")
                        .description(ex.getMessage())
                        .build()
        );
        response.setPath(exchange.getRequest().getPath().value());
        
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleBindException(
            WebExchangeBindException ex, ServerWebExchange exchange) {
        
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        
        log.warn("Validation errors: {} - Path: {}", errors, exchange.getRequest().getPath());
        
        ApiResponse<Void> response = ApiResponse.error(
                "Validation failed",
                ApiResponse.ErrorDetails.builder()
                        .code("VALIDATION_ERROR")
                        .description("One or more fields failed validation")
                        .details(errors)
                        .build()
        );
        response.setPath(exchange.getRequest().getPath().value());
        
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response));
    }

    @ExceptionHandler(AuthenticationException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleAuthenticationException(
            AuthenticationException ex, ServerWebExchange exchange) {
        
        log.warn("Authentication error: {} - Path: {}", ex.getMessage(), exchange.getRequest().getPath());
        
        ApiResponse<Void> response = ApiResponse.error(
                ex.getMessage(),
                ApiResponse.ErrorDetails.builder()
                        .code("AUTHENTICATION_ERROR")
                        .description(ex.getMessage())
                        .build()
        );
        response.setPath(exchange.getRequest().getPath().value());
        
        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleAccessDeniedException(
            AccessDeniedException ex, ServerWebExchange exchange) {
        
        log.warn("Access denied: {} - Path: {}", ex.getMessage(), exchange.getRequest().getPath());
        
        ApiResponse<Void> response = ApiResponse.error(
                ex.getMessage(),
                ApiResponse.ErrorDetails.builder()
                        .code("ACCESS_DENIED")
                        .description(ex.getMessage())
                        .build()
        );
        response.setPath(exchange.getRequest().getPath().value());
        
        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).body(response));
    }

    // Spring Security's own exceptions (e.g. thrown by @PreAuthorize) share simple names with ours,
    // hence the fully-qualified types. The message is generic and only the code and path are logged.
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleSecurityAccessDeniedException(
            org.springframework.security.access.AccessDeniedException ex, ServerWebExchange exchange) {

        log.warn("Authorization failure: ACCESS_DENIED - Path: {}", exchange.getRequest().getPath());

        ApiResponse<Void> response = ApiResponse.error(
                "Access denied",
                ApiResponse.ErrorDetails.builder()
                        .code("ACCESS_DENIED")
                        .description("You do not have permission to perform this action")
                        .build()
        );
        response.setPath(exchange.getRequest().getPath().value());

        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).body(response));
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleSecurityAuthenticationException(
            org.springframework.security.core.AuthenticationException ex, ServerWebExchange exchange) {

        log.warn("Authorization failure: UNAUTHENTICATED - Path: {}", exchange.getRequest().getPath());

        ApiResponse<Void> response = ApiResponse.error(
                "Authentication required",
                ApiResponse.ErrorDetails.builder()
                        .code("UNAUTHENTICATED")
                        .description("A valid access token is required")
                        .build()
        );
        response.setPath(exchange.getRequest().getPath().value());

        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleGenericException(
            Exception ex, ServerWebExchange exchange) {
        
        log.error("Unexpected error: {} - Path: {}", ex.getMessage(), exchange.getRequest().getPath(), ex);
        
        ApiResponse<Void> response = ApiResponse.error(
                "An unexpected error occurred",
                ApiResponse.ErrorDetails.builder()
                        .code("INTERNAL_ERROR")
                        .description("Please contact support if the problem persists")
                        .build()
        );
        response.setPath(exchange.getRequest().getPath().value());
        
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response));
    }
}
