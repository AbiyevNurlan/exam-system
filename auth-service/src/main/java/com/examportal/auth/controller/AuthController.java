package com.examportal.auth.controller;

import com.examportal.auth.service.AuthService;
import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.auth.*;
import com.examportal.common.utils.LogMaskUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * Authentication Controller
 * REST endpoints for user authentication and authorization
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "User authentication and authorization endpoints")
public class AuthController {

    private final AuthService authService;

    /**
     * Register a new user
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register new user", description = "Create a new user account with email and password")
    public Mono<ResponseEntity<ApiResponse<AuthResponse>>> register(
            @Valid @RequestBody RegisterRequest request) {
        
        log.info("Registration request received for email: {}", LogMaskUtils.maskEmail(request.getEmail()));
        
        return authService.register(request)
                .map(authResponse -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(ApiResponse.success(authResponse, "User registered successfully")))
                .doOnSuccess(response -> log.info("User registered: {}", LogMaskUtils.maskEmail(request.getEmail())));
    }

    /**
     * Login user
     */
    @PostMapping("/login")
    @Operation(summary = "User login", description = "Authenticate user and return access and refresh tokens")
    public Mono<ResponseEntity<ApiResponse<AuthResponse>>> login(
            @Valid @RequestBody LoginRequest request) {
        
        log.info("Login request received for email: {}", LogMaskUtils.maskEmail(request.getEmail()));
        
        return authService.login(request)
                .map(authResponse -> ResponseEntity.ok(
                        ApiResponse.success(authResponse, "Login successful")))
                .doOnSuccess(response -> log.info("User logged in: {}", LogMaskUtils.maskEmail(request.getEmail())));
    }

    /**
     * Refresh access token
     */
    @PostMapping("/refresh")
    @Operation(summary = "Refresh access token", description = "Get a new access token using refresh token")
    public Mono<ResponseEntity<ApiResponse<AuthResponse>>> refreshToken(
            @Valid @RequestBody RefreshTokenRequest request) {
        
        log.debug("Token refresh request received");
        
        return authService.refreshToken(request)
                .map(authResponse -> ResponseEntity.ok(
                        ApiResponse.success(authResponse, "Token refreshed successfully")))
                .doOnSuccess(response -> log.debug("Token refreshed successfully"));
    }

    /**
     * Logout user
     */
    @PostMapping("/logout")
    @Operation(summary = "User logout", description = "Invalidate refresh token and logout user")
    public Mono<ResponseEntity<ApiResponse<Void>>> logout(
            @Valid @RequestBody RefreshTokenRequest request) {
        
        log.debug("Logout request received");
        
        return authService.logout(request.getRefreshToken())
                .then(Mono.just(ResponseEntity.ok(
                        ApiResponse.<Void>success(null, "Logged out successfully"))))
                .doOnSuccess(response -> log.info("User logged out"));
    }

    /**
     * Request password reset
     */
    @PostMapping("/forgot-password")
    @Operation(summary = "Forgot password", description = "Request password reset link via email")
    public Mono<ResponseEntity<ApiResponse<Void>>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        
        log.info("Password reset requested for email: {}", LogMaskUtils.maskEmail(request.getEmail()));
        
        return authService.forgotPassword(request)
                .then(Mono.just(ResponseEntity.ok(
                        ApiResponse.<Void>success(null, "Password reset instructions sent to email"))))
                .doOnSuccess(response -> log.info("Password reset email sent: {}",
                        LogMaskUtils.maskEmail(request.getEmail())));
    }

    /**
     * Reset password with token
     */
    @PostMapping("/reset-password")
    @Operation(summary = "Reset password", description = "Reset user password using reset token")
    public Mono<ResponseEntity<ApiResponse<Void>>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        
        log.info("Password reset attempt");
        
        return authService.resetPassword(request)
                .then(Mono.just(ResponseEntity.ok(
                        ApiResponse.<Void>success(null, "Password reset successfully"))))
                .doOnSuccess(response -> log.info("Password reset successful"));
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    @Operation(summary = "Health check", description = "Check if auth service is running")
    public Mono<ResponseEntity<ApiResponse<String>>> health() {
        return Mono.just(ResponseEntity.ok(
                ApiResponse.success("Auth service is running", "Service is healthy")));
    }
}
