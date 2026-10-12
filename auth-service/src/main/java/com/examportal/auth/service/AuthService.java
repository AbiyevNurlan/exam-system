package com.examportal.auth.service;

import com.examportal.auth.client.GroupInviteClient;
import com.examportal.auth.exception.AccountLockedException;
import com.examportal.auth.exception.DuplicateEmailException;
import com.examportal.auth.exception.EmailNotVerifiedException;
import com.examportal.auth.exception.InvalidCredentialsException;
import com.examportal.auth.exception.InvalidOtpException;
import com.examportal.auth.exception.InvalidTokenException;
import com.examportal.auth.mail.VerificationMailer;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
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
    private final GroupInviteClient groupInviteClient;
    private final OtpService otpService;
    private final VerificationMailer verificationMailer;

    private static final int PASSWORD_RESET_TOKEN_EXPIRY_HOURS = 1;

    @Value("${auth.lockout.max-attempts:5}")
    private int lockoutMaxAttempts;

    @Value("${auth.lockout.duration-minutes:15}")
    private int lockoutDurationMinutes; // 0 = locked until an admin unlocks

    /**
     * Register a new user. The account starts unverified and gets no tokens: a verification code is
     * emailed, and only verifyOtp turns it into a usable account.
     */
    public Mono<Void> register(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());
        log.info("Registering new user with email: {}", maskEmail(email));

        // Check if email already exists
        return userRepository.existsByEmail(email)
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new DuplicateEmailException(email));
                    }
                    // A concurrent registration can still hit the unique index
                    return createUser(request, email)
                            .onErrorMap(DuplicateKeyException.class, e -> new DuplicateEmailException(
                                    "User with email '" + email + "' already exists", e));
                })
                .flatMap(user -> otpService.issue(user.getId(), OtpService.PURPOSE_EMAIL_VERIFICATION))
                // @Transactional is not applied at runtime in this service, so the TransactionalOperator is the real
                // transaction boundary: the user and the first code are stored together
                .as(transactionalOperator::transactional)
                // After the commit, detached: a mail failure never undoes or fails the registration
                .doOnNext(code -> verificationMailer.sendVerificationCode(email, code, otpService.getTtlMinutes()))
                .doOnSuccess(code -> log.info("User registered, verification pending: {}", maskEmail(email)))
                .then();
    }

    /**
     * Verify the email address with the emailed code and log the user in.
     * Every failure is the same InvalidOtpException (see there).
     */
    public Mono<AuthResponse> verifyOtp(VerifyOtpRequest request) {
        String email = normalizeEmail(request.getEmail());
        log.info("Email verification attempt for: {}", maskEmail(email));

        return userRepository.findByEmail(email)
                .filter(user -> user.isActive() && !user.isEmailVerified())
                .flatMap(user -> otpService.verify(user.getId(), OtpService.PURPOSE_EMAIL_VERIFICATION, request.getCode())
                        .flatMap(valid -> valid
                                ? userRepository.verifyEmail(user.getId()).thenReturn(true)
                                : Mono.just(false))
                        // The code is consumed and the address marked verified together. A wrong code is
                        // not an error at this point, so its attempt counter is committed as well.
                        .as(transactionalOperator::transactional)
                        .filter(valid -> valid)
                        .map(valid -> {
                            user.setEmailVerified(true);
                            return user;
                        }))
                .switchIfEmpty(Mono.error(new InvalidOtpException()))
                .doOnNext(user -> log.info("Email verified for: {}", maskEmail(email)))
                // Group invites imported for this now verified email become memberships. Same hook as on
                // login; it never fails the verification (a failure is logged and the next login retries).
                .flatMap(user -> groupInviteClient.applyForVerifiedUser(user).thenReturn(user))
                // The address is verified either way; tokens are still refused while the account is locked
                .flatMap(this::unlockIfLockExpired)
                .flatMap(user -> user.isAccountLocked()
                        ? Mono.<User>error(new AccountLockedException())
                        : Mono.just(user))
                .flatMap(this::generateAuthResponse);
    }

    /**
     * Send a new verification code. Completes the same way whether or not the email is registered,
     * already verified, or held back by the cooldown or the hourly limit: the caller learns nothing.
     */
    public Mono<Void> resendOtp(ResendOtpRequest request) {
        String email = normalizeEmail(request.getEmail());
        log.info("Verification code resend requested for: {}", maskEmail(email));

        return userRepository.findByEmail(email)
                .filter(user -> user.isActive() && !user.isEmailVerified())
                .flatMap(user -> otpService.issue(user.getId(), OtpService.PURPOSE_EMAIL_VERIFICATION))
                .doOnNext(code -> verificationMailer.sendVerificationCode(email, code, otpService.getTtlMinutes()))
                .then()
                // Nothing here is an expected client error and the failure never reaches the exception
                // handler, so it is logged here; the answer stays the same
                .onErrorResume(e -> {
                    log.error("Verification code resend failed for {}; answering success anyway", maskEmail(email), e);
                    return Mono.empty();
                });
    }

    /**
     * Authenticate user and generate tokens
     */
    public Mono<AuthResponse> login(LoginRequest request) {
        String email = normalizeEmail(request.getEmail());
        log.info("Login attempt for email: {}", maskEmail(email));

        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new InvalidCredentialsException()))
                .flatMap(this::unlockIfLockExpired)
                .flatMap(user -> validateUserAndPassword(user, request.getPassword()))
                .flatMap(user -> {
                    // Reset failed login attempts on successful login
                    return userRepository.resetFailedLoginAttempts(user.getId())
                            .thenReturn(user);
                })
                // Only after the password was accepted: no tokens before the email address is verified
                .flatMap(user -> user.isEmailVerified()
                        ? Mono.just(user)
                        : Mono.<User>error(new EmailNotVerifiedException()))
                // Group invites imported for this (verified) email become memberships; never fails the login
                .flatMap(user -> groupInviteClient.applyForVerifiedUser(user).thenReturn(user))
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
                            String accessToken = jwtTokenProvider.generateAccessToken(
                                    user.getId(),
                                    user.getEmail(),
                                    user.getRole().name()
                            );

                            // Rotate refresh token for security
                            return tokenService.rotateRefreshToken(request.getRefreshToken(), user.getId())
                                    .map(newRefreshToken -> buildAuthResponse(user, accessToken, newRefreshToken.getToken()));
                        })
                )
                .doOnSuccess(response -> log.info("Access token refreshed successfully"));
    }

    /**
     * Logout user by revoking refresh token
     */
    public Mono<Void> logout(String refreshToken) {
        log.debug("Logging out user");

        return tokenService.revokeRefreshToken(refreshToken)
                .doOnSuccess(v -> log.info("User logged out successfully"));
    }

    /**
     * Request password reset
     */
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
                // Always return success to prevent email enumeration. Nothing in this flow is an expected
                // client error and the failure never reaches the exception handler, so it is logged here.
                .onErrorResume(e -> {
                    log.error("Password reset request failed for {}; answering success anyway", maskEmail(email), e);
                    return Mono.empty();
                });
    }

    /**
     * Reset password with token
     */
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
        // Check if account is locked (before the password, so a correct password never logs in)
        if (user.isAccountLocked()) {
            log.info("Login attempt for locked account: {}", maskEmail(user.getEmail()));
            return Mono.error(new AccountLockedException());
        }

        // Check if account is active
        if (!user.isActive()) {
            log.info("Login attempt for inactive account: {}", maskEmail(user.getEmail()));
            return Mono.error(new InvalidCredentialsException());
        }

        // Validate password
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            log.info("Invalid password for user: {}", maskEmail(user.getEmail()));
            return Mono.error(new InvalidCredentialsException());
        }

        return Mono.just(user);
    }

    /**
     * Lift a temporary lock that has run out, so the login continues with a fresh attempt counter
     */
    private Mono<User> unlockIfLockExpired(User user) {
        if (!user.isAccountLocked()) {
            return Mono.just(user);
        }
        return userRepository.unlockIfLockExpired(user.getId())
                .map(updated -> {
                    if (updated > 0) {
                        log.info("Temporary lock expired, account unlocked: {}", maskEmail(user.getEmail()));
                        user.setAccountLocked(false);
                        user.setLockedUntil(null);
                        user.setFailedLoginAttempts(0);
                    }
                    return user;
                });
    }

    /**
     * Handle failed login attempt.
     * The counter and the lock are one atomic SQL statement; no transaction annotation is involved.
     */
    private Mono<Void> handleFailedLogin(String email) {
        return userRepository.registerFailedLogin(email, lockoutMaxAttempts, lockoutDurationMinutes)
                .doOnNext(locked -> {
                    if (locked) {
                        log.warn("Account locked due to too many failed attempts: {}", maskEmail(email));
                    }
                })
                .then()
                // The caller still answers 401; a failure here must not change the response
                .onErrorResume(e -> {
                    log.error("Could not record failed login: {}", e.getClass().getSimpleName());
                    return Mono.empty();
                });
    }

    /**
     * Generate auth response with tokens
     */
    private Mono<AuthResponse> generateAuthResponse(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(),
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
                        .id(user.getId())
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
