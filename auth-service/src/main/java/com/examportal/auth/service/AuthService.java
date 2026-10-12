package com.examportal.auth.service;

import com.examportal.auth.exception.AccountLockedException;
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
import com.examportal.common.utils.LogMaskUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Locale;
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
    private final TransactionalOperator transactionalOperator;

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int PASSWORD_RESET_TOKEN_EXPIRY_HOURS = 1;

    /**
     * Register a new user
     */
    @Transactional
    public Mono<AuthResponse> register(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());
        log.info("Registering new user with email: {}", maskEmail(email));

        // Check if email already exists
        return userRepository.existsByEmail(email)
                .flatMap(exists -> {
                    if (exists) {
                        log.warn("Registration failed: Email already exists - {}", maskEmail(email));
                        return Mono.error(new DuplicateEmailException(email));
                    }
                    // A concurrent registration can still hit the unique index
                    return createUser(request, email)
                            .onErrorMap(DuplicateKeyException.class, e -> new DuplicateEmailException(
                                    "User with email '" + email + "' already exists", e));
                })
                .flatMap(user -> generateAuthResponse(user))
                .doOnSuccess(response -> log.info("User registered successfully: {}", maskEmail(email)))
                .doOnError(error -> log.error("Registration failed for email: {} - {}",
                        maskEmail(email), error.getClass().getSimpleName()))
                // @Transactional is not applied at runtime in this service, so the TransactionalOperator is the real transaction boundary
                .as(transactionalOperator::transactional);
    }

    /**
     * Authenticate user and generate tokens
     */
    @Transactional
    public Mono<AuthResponse> login(LoginRequest request) {
        String email = normalizeEmail(request.getEmail());
        log.info("Login attempt for email: {}", maskEmail(email));

        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new InvalidCredentialsException()))
                .flatMap(user -> validateUserAndPassword(user, request.getPassword()))
                .flatMap(user -> {
                    // Reset failed login attempts on successful login
                    return userRepository.resetFailedLoginAttempts(user.getId())
                            .thenReturn(user);
                })
                .flatMap(this::generateAuthResponse)
                .doOnSuccess(response -> log.info("Login successful for email: {}", maskEmail(email)))
                .onErrorResume(InvalidCredentialsException.class, error -> 
                    handleFailedLogin(email).then(Mono.error(error)));
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
        String email = normalizeEmail(request.getEmail());
        log.info("Password reset requested for email: {}", maskEmail(email));

        return userRepository.findByEmail(email)
                .flatMap(user -> {
                    // Delete any existing reset tokens for this user
                    return passwordResetTokenRepository.deleteAllByUserId(user.getId())
                            .then(createPasswordResetToken(user.getId()))
                            .flatMap(token -> {
                                // In production, send email with reset link (the token itself must never be logged)
                                log.info("Password reset token created for user: {}", maskEmail(user.getEmail()));
                                // TODO: Send email with reset link
                                return Mono.empty();
                            });
                })
                .then()
                .doOnSuccess(v -> log.info("Password reset request processed for: {}", maskEmail(email)))
                .doOnError(error -> log.error("Password reset request failed for: {} - {}",
                        maskEmail(email), error.getClass().getSimpleName()))
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
                .doOnError(error -> log.error("Password reset failed", error))
                // Password update, token consumption and session revocation must succeed or fail together
                .as(transactionalOperator::transactional);
    }

    // ==================== Private Helper Methods ====================

    /**
     * Trim and lowercase an email so it matches the lower(email) unique index
     */
    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Mask an email for logging, keeping only the first character and the domain
     */
    private String maskEmail(String email) {
        return LogMaskUtils.maskEmail(email);
    }

    /**
     * Create a new user entity
     */
    private Mono<User> createUser(RegisterRequest request, String email) {
        String hashedPassword = passwordEncoder.encode(request.getPassword());

        User user = User.builder()
                .email(email)
                .passwordHash(hashedPassword)
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phoneNumber(request.getPhoneNumber())
                // Self-registration always creates a STUDENT; any client-supplied role is ignored
                .role(UserRole.STUDENT)
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
            log.warn("Login attempt for locked account: {}", maskEmail(user.getEmail()));
            return Mono.error(new AccountLockedException());
        }

        // Check if account is active
        if (!user.isActive()) {
            log.warn("Login attempt for inactive account: {}", maskEmail(user.getEmail()));
            return Mono.error(new InvalidCredentialsException());
        }

        // Validate password
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            log.warn("Invalid password for user: {}", maskEmail(user.getEmail()));
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
                        log.warn("Account locked due to too many failed attempts: {}", maskEmail(email));
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
