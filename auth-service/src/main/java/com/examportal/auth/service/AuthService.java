package com.examportal.auth.service;

import com.examportal.auth.exception.DuplicateEmailException;
import com.examportal.auth.exception.InvalidCredentialsException;
import com.examportal.auth.exception.InvalidTokenException;
import com.examportal.auth.repository.PasswordResetTokenRepository;
import com.examportal.auth.repository.UserRepository;
import com.examportal.common.domain.dto.auth.*;
import com.examportal.common.domain.entity.PasswordResetToken;
import com.examportal.common.domain.entity.User;
import com.examportal.common.domain.entity.UserRole;
import com.examportal.common.security.JwtTokenProvider;
import com.examportal.common.utils.CodeGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Authentication Service
 * Core business logic for user authentication and authorization
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int PASSWORD_RESET_TOKEN_EXPIRY_HOURS = 1;

    /**
     * Register a new user
     */
    @Transactional
    public Mono<AuthResponse> register(RegisterRequest request) {
        log.info("Registering new user with email: {}", request.getEmail());

        // Check if email already exists
        return userRepository.existsByEmail(request.getEmail())
                .flatMap(exists -> {
                    if (exists) {
                        log.warn("Registration failed: Email already exists - {}", request.getEmail());
                        return Mono.error(new DuplicateEmailException(request.getEmail()));
                    }
                    return createUser(request);
                })
                .flatMap(user -> generateAuthResponse(user))
                .doOnSuccess(response -> log.info("User registered successfully: {}", request.getEmail()))
                .doOnError(error -> log.error("Registration failed for email: {}", request.getEmail(), error));
    }

    /**
     * Authenticate user and generate tokens
     */
    @Transactional
    public Mono<AuthResponse> login(LoginRequest request) {
        log.info("Login attempt for email: {}", request.getEmail());

        return userRepository.findByEmail(request.getEmail())
                .switchIfEmpty(Mono.error(new InvalidCredentialsException()))
                .flatMap(user -> validateUserAndPassword(user, request.getPassword()))
                .flatMap(user -> {
                    // Reset failed login attempts on successful login
                    return userRepository.resetFailedLoginAttempts(user.getId())
                            .thenReturn(user);
                })
                .flatMap(this::generateAuthResponse)
                .doOnSuccess(response -> log.info("Login successful for email: {}", request.getEmail()))
                .onErrorResume(InvalidCredentialsException.class, error -> 
                    handleFailedLogin(request.getEmail()).then(Mono.error(error)));
    }

    /**
     * Refresh access token using refresh token
     */
    public Mono<AuthResponse> refreshToken(RefreshTokenRequest request) {
        log.debug("Refreshing access token");

        return tokenService.validateRefreshToken(request.getRefreshToken())
                .flatMap(refreshToken -> userRepository.findById(refreshToken.getUserId())
                        .switchIfEmpty(Mono.error(new InvalidTokenException("User not found")))
                        .flatMap(user -> {
                            // Generate new access token
                            Long userId = Long.valueOf(user.getId().hashCode() & Integer.MAX_VALUE);
                            String accessToken = jwtTokenProvider.generateAccessToken(
                                    userId,
                                    user.getEmail(),
                                    user.getRole().name()
                            );

                            // Rotate refresh token for security
                            return tokenService.rotateRefreshToken(request.getRefreshToken(), user.getId())
                                    .map(newRefreshToken -> buildAuthResponse(user, accessToken, newRefreshToken.getToken()));
                        })
                )
                .doOnSuccess(response -> log.info("Access token refreshed successfully"))
                .doOnError(error -> log.error("Token refresh failed", error));
    }

    /**
     * Logout user by revoking refresh token
     */
    public Mono<Void> logout(String refreshToken) {
        log.debug("Logging out user");

        return tokenService.revokeRefreshToken(refreshToken)
                .doOnSuccess(v -> log.info("User logged out successfully"))
                .doOnError(error -> log.error("Logout failed", error));
    }

    /**
     * Request password reset
     */
    @Transactional
    public Mono<Void> forgotPassword(ForgotPasswordRequest request) {
        log.info("Password reset requested for email: {}", request.getEmail());

        return userRepository.findByEmail(request.getEmail())
                .flatMap(user -> {
                    // Delete any existing reset tokens for this user
                    return passwordResetTokenRepository.deleteAllByUserId(user.getId())
                            .then(createPasswordResetToken(user.getId()))
                            .flatMap(token -> {
                                // In production, send email with reset link
                                log.info("Password reset token created for user: {} - Token: {}", 
                                        user.getEmail(), token.getToken());
                                // TODO: Send email with reset link
                                return Mono.empty();
                            });
                })
                .then()
                .doOnSuccess(v -> log.info("Password reset token created for: {}", request.getEmail()))
                .doOnError(error -> log.error("Password reset request failed for: {}", request.getEmail(), error))
                // Always return success to prevent email enumeration
                .onErrorResume(e -> {
                    log.warn("Error in forgot password, returning success anyway to prevent enumeration");
                    return Mono.empty();
                });
    }

    /**
     * Reset password with token
     */
    @Transactional
    public Mono<Void> resetPassword(ResetPasswordRequest request) {
        log.info("Password reset attempt with token");

        return passwordResetTokenRepository.findValidTokenByToken(request.getToken())
                .switchIfEmpty(Mono.error(new InvalidTokenException("Invalid or expired reset token")))
                .flatMap(token -> {
                    // Hash new password
                    String hashedPassword = passwordEncoder.encode(request.getNewPassword());

                    // Update user password
                    return userRepository.updatePasswordHash(token.getUserId(), hashedPassword)
                            .then(passwordResetTokenRepository.markTokenAsUsed(token.getToken()))
                            .then(tokenService.revokeAllUserTokens(token.getUserId()))
                            .then();
                })
                .doOnSuccess(v -> log.info("Password reset successful"))
                .doOnError(error -> log.error("Password reset failed", error));
    }

    // ==================== Private Helper Methods ====================

    /**
     * Create a new user entity
     */
    private Mono<User> createUser(RegisterRequest request) {
        String hashedPassword = passwordEncoder.encode(request.getPassword());

        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(hashedPassword)
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phoneNumber(request.getPhoneNumber())
                .role(UserRole.valueOf(request.getRole()))
                .emailVerified(false)
                .accountLocked(false)
                .failedLoginAttempts(0)
                .active(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        return userRepository.save(user);
    }

    /**
     * Validate user status and password
     */
    private Mono<User> validateUserAndPassword(User user, String password) {
        // Check if account is locked
        if (user.isAccountLocked()) {
            log.warn("Login attempt for locked account: {}", user.getEmail());
            return Mono.error(new InvalidCredentialsException("Account is locked"));
        }

        // Check if account is active
        if (!user.isActive()) {
            log.warn("Login attempt for inactive account: {}", user.getEmail());
            return Mono.error(new InvalidCredentialsException("Account is inactive"));
        }

        // Validate password
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            log.warn("Invalid password for user: {}", user.getEmail());
            return Mono.error(new InvalidCredentialsException());
        }

        return Mono.just(user);
    }

    /**
     * Handle failed login attempt
     */
    private Mono<Void> handleFailedLogin(String email) {
        return userRepository.findByEmail(email)
                .flatMap(user -> {
                    int attempts = user.getFailedLoginAttempts() + 1;
                    
                    if (attempts >= MAX_FAILED_ATTEMPTS) {
                        log.warn("Account locked due to too many failed attempts: {}", email);
                        return userRepository.lockAccount(user.getId()).then();
                    } else {
                        return userRepository.incrementFailedLoginAttempts(user.getId()).then();
                    }
                })
                .onErrorResume(e -> Mono.empty()); // Ignore errors to prevent enumeration
    }

    /**
     * Generate auth response with tokens
     */
    private Mono<AuthResponse> generateAuthResponse(User user) {
        Long userId = Long.valueOf(user.getId().hashCode() & Integer.MAX_VALUE);
        String accessToken = jwtTokenProvider.generateAccessToken(
                userId,
                user.getEmail(),
                user.getRole().name()
        );

        return tokenService.createRefreshToken(user.getId())
                .map(refreshToken -> buildAuthResponse(user, accessToken, refreshToken.getToken()));
    }

    /**
     * Build auth response DTO
     */
    private AuthResponse buildAuthResponse(User user, String accessToken, String refreshToken) {
        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getAccessTokenExpiration())
                .user(AuthResponse.UserInfo.builder()
                        .id(Long.valueOf(user.getId().hashCode() & Integer.MAX_VALUE))
                        .email(user.getEmail())
                        .firstName(user.getFirstName())
                        .lastName(user.getLastName())
                        .role(user.getRole().name())
                        .emailVerified(user.isEmailVerified())
                        .build())
                .build();
    }

    /**
     * Create password reset token
     */
    private Mono<PasswordResetToken> createPasswordResetToken(UUID userId) {
        String tokenValue = CodeGenerator.generateSecureToken(48);
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(PASSWORD_RESET_TOKEN_EXPIRY_HOURS);

        PasswordResetToken token = PasswordResetToken.builder()
                .userId(userId)
                .token(tokenValue)
                .expiresAt(expiresAt)
                .used(false)
                .createdAt(LocalDateTime.now())
                .build();

        return passwordResetTokenRepository.save(token);
    }
}
