package com.examportal.auth.service;

import com.examportal.auth.client.GroupInviteClient;
import com.examportal.auth.exception.AccountLockedException;
import com.examportal.auth.exception.DuplicateEmailException;
import com.examportal.auth.exception.EmailNotVerifiedException;
import com.examportal.auth.exception.InvalidCredentialsException;
import com.examportal.auth.exception.InvalidOtpException;
import com.examportal.auth.exception.InvalidTokenException;
import com.examportal.auth.exception.PasswordChangeRejectedException;
import com.examportal.auth.mail.VerificationMailer;
import com.examportal.auth.repository.UserRepository;
import com.examportal.common.domain.PhoneNumbers;
import com.examportal.common.domain.dto.auth.*;
import com.examportal.common.domain.entity.User;
import com.examportal.common.domain.entity.UserRole;
import com.examportal.common.exception.ApplicationException;
import com.examportal.common.exception.ValidationException;
import com.examportal.common.security.JwtTokenProvider;
import com.examportal.common.utils.LogMaskUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
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
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final TransactionalOperator transactionalOperator;
    private final GroupInviteClient groupInviteClient;
    private final OtpService otpService;
    private final VerificationMailer verificationMailer;
    private final AccountAuditLog accountAuditLog;

    @Value("${auth.lockout.max-attempts:5}")
    private int lockoutMaxAttempts;

    @Value("${auth.lockout.duration-minutes:15}")
    private int lockoutDurationMinutes; // 0 = locked until an admin unlocks

    /**
     * Register a new user. The account starts unverified and gets no tokens: a verification code is
     * emailed, and only verifyOtp turns it into a usable account.
     *
     * An email that is registered but was never verified does not belong to anyone yet: whoever
     * registered it first may not own the mailbox. The new registration therefore takes the account
     * over (password and name replaced, every older code invalidated) and answers exactly like a
     * fresh one. Only a verified account is a conflict.
     */
    public Mono<Void> register(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());
        log.info("Registering new user with email: {}", maskEmail(email));

        // The row lock makes parallel registrations of one unverified email take turns
        return userRepository.findByEmailForUpdate(email)
                .flatMap(existing -> existing.isEmailVerified()
                        ? Mono.<User>error(new DuplicateEmailException(email))
                        : replaceUnverifiedUser(existing, request))
                // A concurrent registration can still hit the unique index
                .switchIfEmpty(Mono.defer(() -> createUser(request, email)
                        .onErrorMap(DuplicateKeyException.class, e -> new DuplicateEmailException(
                                "User with email '" + email + "' already exists", e))
                        .onErrorMap(AuthService::participantCodesExhausted, e -> new ApplicationException(
                                "No participant code is left: new accounts cannot be created",
                                HttpStatus.SERVICE_UNAVAILABLE, "PARTICIPANT_CODES_EXHAUSTED", e))))
                // Empty when the cooldown or the hourly limit refuses a new code: no email, same answer
                .flatMap(user -> otpService.issue(user.getId(), OtpService.PURPOSE_EMAIL_VERIFICATION))
                // @Transactional is not applied at runtime in this service, so the TransactionalOperator is the real
                // transaction boundary: the user (new or replaced) and the code are stored together
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
     * Email a password reset code. Completes the same way whether or not the email belongs to an
     * account, and when the cooldown or the hourly limit holds the code back: the caller learns nothing.
     * Only a verified account can reset: an unverified one simply registers again.
     */
    public Mono<Void> forgotPassword(ForgotPasswordRequest request) {
        String email = normalizeEmail(request.getEmail());
        log.info("Password reset requested for email: {}", maskEmail(email));

        return userRepository.findByEmail(email)
                .filter(user -> user.isActive() && user.isEmailVerified())
                .flatMap(user -> otpService.issue(user.getId(), OtpService.PURPOSE_PASSWORD_RESET))
                .doOnNext(code -> verificationMailer.sendPasswordResetCode(email, code, otpService.getTtlMinutes()))
                .then()
                // Nothing here is an expected client error and the failure never reaches the exception
                // handler, so it is logged here; the answer stays the same
                .onErrorResume(e -> {
                    log.error("Password reset request failed for {}; answering success anyway", maskEmail(email), e);
                    return Mono.empty();
                });
    }

    /**
     * Set a new password with the emailed code.
     * Every failure is the same InvalidOtpException (see there).
     */
    public Mono<Void> resetPassword(ResetPasswordRequest request) {
        String email = normalizeEmail(request.getEmail());
        log.info("Password reset attempt for: {}", maskEmail(email));

        return userRepository.findByEmail(email)
                .filter(user -> user.isActive() && user.isEmailVerified())
                .flatMap(user -> otpService.verify(user.getId(), OtpService.PURPOSE_PASSWORD_RESET, request.getCode())
                        .flatMap(valid -> valid
                                // The owner of the mailbox is back in control: new password, every
                                // session ended, lock and failed-login counter cleared
                                ? userRepository.updatePasswordHash(user.getId(),
                                                passwordEncoder.encode(request.getNewPassword()))
                                        .then(userRepository.unlockAccount(user.getId()))
                                        .then(tokenService.revokeAllUserTokens(user.getId()))
                                        .thenReturn(true)
                                : Mono.just(false))
                        // Code consumption, password update and session revocation succeed or fail together.
                        // A wrong code is not an error at this point, so its attempt counter is committed as well.
                        .as(transactionalOperator::transactional)
                        .filter(valid -> valid))
                .switchIfEmpty(Mono.error(new InvalidOtpException()))
                .doOnNext(valid -> log.info("Password reset successful for: {}", maskEmail(email)))
                .then();
    }

    // ==================== The own account ====================

    /**
     * GET /auth/me: the user of the token, read from the database. A user that is gone or
     * deactivated answers 401, like a revoked token.
     */
    public Mono<CurrentUserResponse> currentUser(UUID userId) {
        return userRepository.findById(userId)
                .filter(User::isActive)
                .switchIfEmpty(Mono.error(new InvalidTokenException("User not found")))
                .map(this::toCurrentUser);
    }

    /**
     * PUT /auth/profile: first name, last name and phone of the own account (an empty phone removes it).
     * The change and its audit row are written together.
     */
    public Mono<CurrentUserResponse> updateProfile(UUID userId, UpdateProfileRequest request) {
        String firstName = request.getFirstName().trim();
        String lastName = request.getLastName().trim();
        if (firstName.length() < 2 || lastName.length() < 2) {
            return Mono.error(new ValidationException("First and last name must have at least 2 characters"));
        }
        String phone;
        try {
            // Azerbaijani numbers only, stored as E.164 (+994XXXXXXXXX); empty removes it
            phone = PhoneNumbers.toE164(request.getPhone());
        } catch (IllegalArgumentException invalid) {
            return Mono.error(new ValidationException("phone", invalid.getMessage()));
        }

        Mono<CurrentUserResponse> change = userRepository.updateProfile(userId, firstName, lastName, phone)
                .flatMap(updated -> updated == 1
                        ? accountAuditLog.record(userId, AccountAuditLog.PROFILE_UPDATED)
                        : Mono.<Void>error(new InvalidTokenException("User not found")))
                .then(Mono.defer(() -> userRepository.findById(userId)))
                .map(this::toCurrentUser);
        return transactionalOperator.transactional(change)
                .doOnSuccess(user -> log.info("Profile updated by user {}", userId));
    }

    /**
     * POST /auth/change-password. The current password must match; the new one follows the
     * registration policy (ChangePasswordRequest) and must differ from it. On success every refresh
     * token of the user is revoked (all other sessions are signed out) and a new pair is returned for
     * this one; access tokens already issued stay valid until they expire (at most 15 minutes).
     * A wrong current password, a gone or a deactivated account all answer the same 400
     * PASSWORD_CHANGE_REJECTED. The row lock makes a change and a reset of the same account take turns.
     */
    public Mono<AuthResponse> changePassword(UUID userId, ChangePasswordRequest request) {
        Mono<User> change = userRepository.findByIdForUpdate(userId)
                .filter(User::isActive)
                .filter(user -> passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash()))
                .switchIfEmpty(Mono.error(new PasswordChangeRejectedException()))
                .flatMap(user -> passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())
                        ? Mono.<User>error(new ApplicationException("The new password must differ from the current one",
                                HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED"))
                        : userRepository.updatePasswordHash(userId, passwordEncoder.encode(request.getNewPassword()))
                                .then(tokenService.revokeAllUserTokens(userId))
                                .then(accountAuditLog.record(userId, AccountAuditLog.PASSWORD_CHANGED))
                                .thenReturn(user));

        // New password, revoked sessions and the audit row commit together; the new pair comes after the commit
        return transactionalOperator.transactional(change)
                .flatMap(this::generateAuthResponse)
                .doOnSuccess(response -> log.info("Password changed by user {}; every refresh token revoked", userId))
                .doOnError(PasswordChangeRejectedException.class,
                        error -> log.info("Password change refused for user {}", userId));
    }

    /**
     * The participant code sequence has given all 9 000 000 codes (it never cycles, so no code repeats):
     * the database refuses the insert with "reached maximum value of sequence participant_code_seq"
     */
    static boolean participantCodesExhausted(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause() == e ? null : e.getCause()) {
            if (e.getMessage() != null && e.getMessage().contains("participant_code_seq")) {
                return true;
            }
        }
        return false;
    }

    private CurrentUserResponse toCurrentUser(User user) {
        return CurrentUserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .participantCode(user.getParticipantCode())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .phoneNumber(user.getPhoneNumber())
                .role(user.getRole().name())
                .emailVerified(user.isEmailVerified())
                .createdAt(user.getCreatedAt())
                .build();
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
     * Replace the password and name of an unverified account with those of the new registration
     * and invalidate its codes. The codes go even when the send limits then refuse a new one:
     * a code that was issued for the previous password must never verify the new one.
     */
    private Mono<User> replaceUnverifiedUser(User existing, RegisterRequest request) {
        log.info("Unverified account re-registered, replacing its credentials: {}", maskEmail(existing.getEmail()));

        return userRepository.replaceUnverifiedAccount(existing.getId(),
                        passwordEncoder.encode(request.getPassword()),
                        request.getFirstName(), request.getLastName(), request.getPhoneNumber())
                .then(otpService.invalidateAll(existing.getId(), OtpService.PURPOSE_EMAIL_VERIFICATION))
                .thenReturn(existing);
    }

    /**
     * Validate user status and password
     */
    private Mono<User> validateUserAndPassword(User user, String password) {
        // Check if account is locked (before the password, so a correct password never logs in).
        // Lockout does not apply to an unverified account: it answers 401 for a wrong password and
        // EMAIL_NOT_VERIFIED for the right one, and its failed logins are not counted.
        if (user.isAccountLocked() && user.isEmailVerified()) {
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
}
