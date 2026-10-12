package com.examportal.common.exception;

import com.examportal.common.domain.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.r2dbc.BadSqlGrammarException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
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

    // The database refused the data (unique, foreign key, not-null or check constraint). The driver's
    // message contains SQL and values, so it is neither returned nor logged.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleDataIntegrityViolation(
            DataIntegrityViolationException ex, ServerWebExchange exchange) {
        
        boolean duplicate = ex instanceof DuplicateKeyException;
        return dataError(exchange,
                duplicate ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST,
                duplicate ? "DATA_CONFLICT" : "DATA_CONSTRAINT_VIOLATION",
                duplicate ? "The request conflicts with existing data" : "The request contains data that cannot be stored");
    }

    // SQLSTATE class 22 (value too long, numeric overflow, bad format) is reported by the driver as
    // "bad grammar" although the statement is fine and the client's data is at fault: 400.
    // Every other grammar error is a real server bug and stays a 500.
    @ExceptionHandler(BadSqlGrammarException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleBadSqlGrammar(
            BadSqlGrammarException ex, ServerWebExchange exchange) {
        
        String sqlState = ex.getR2dbcException() != null ? ex.getR2dbcException().getSqlState() : null;
        if (sqlState != null && sqlState.startsWith("22")) {
            return dataError(exchange, HttpStatus.BAD_REQUEST, "DATA_VALUE_INVALID",
                    "The request contains a value that is too large or malformed");
        }
        return handleGenericException(ex, exchange);
    }

    private Mono<ResponseEntity<ApiResponse<Void>>> dataError(
            ServerWebExchange exchange, HttpStatus status, String code, String message) {
        
        log.warn("Data rejected by the database: {} ({}) - Path: {}", code, status.value(), exchange.getRequest().getPath());
        
        ApiResponse<Void> response = ApiResponse.error(
                message,
                ApiResponse.ErrorDetails.builder()
                        .code(code)
                        .description(message)
                        .build()
        );
        response.setPath(exchange.getRequest().getPath().value());
        
        return Mono.just(ResponseEntity.status(status).body(response));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleOptimisticLockingFailure(
            OptimisticLockingFailureException ex, ServerWebExchange exchange) {

        log.warn("Concurrent modification: CONCURRENT_MODIFICATION - Path: {}", exchange.getRequest().getPath());

        ApiResponse<Void> response = ApiResponse.error(
                "The resource was modified by another request",
                ApiResponse.ErrorDetails.builder()
                        .code("CONCURRENT_MODIFICATION")
                        .description("Reload the resource and try again")
                        .build()
        );
        response.setPath(exchange.getRequest().getPath().value());

        return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(response));
    }

    // Framework errors: unmapped routes (404), malformed body / missing or mistyped parameters
    // (ServerWebInputException, 400), wrong method or media type, etc. The status is kept, the message is
    // generic (the framework's reason can echo request data) and only the code and path are logged.
    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleResponseStatusException(
            ResponseStatusException ex, ServerWebExchange exchange) {

        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        String code = status != null ? status.name() : "HTTP_" + ex.getStatusCode().value();
        String message;
        if (status == HttpStatus.NOT_FOUND) {
            message = "Resource not found";
        } else if (status == HttpStatus.BAD_REQUEST) {
            message = "Malformed request";
        } else {
            message = status != null ? status.getReasonPhrase() : "Request failed";
        }

        log.warn("Request rejected: {} - Path: {}", code, exchange.getRequest().getPath());

        ApiResponse<Void> response = ApiResponse.error(
                message,
                ApiResponse.ErrorDetails.builder()
                        .code(code)
                        .description(message)
                        .build()
        );
        response.setPath(exchange.getRequest().getPath().value());

        return Mono.just(ResponseEntity.status(ex.getStatusCode()).headers(ex.getHeaders()).body(response));
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
