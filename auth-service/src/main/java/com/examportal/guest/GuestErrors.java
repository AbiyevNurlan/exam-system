package com.examportal.guest;

import com.examportal.common.exception.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * The errors of the guest sign-in (B13a), in the body format of every other error (ApiResponse with error.code).
 * The messages carry no email, code or token.
 */
public final class GuestErrors {

    private GuestErrors() {
    }

    /** PAID_ACCESS_ENABLED is false: the guest endpoints answer as if they did not exist */
    public static final String FEATURE_DISABLED = "FEATURE_DISABLED";
    /** The email belongs to an account (users): no guest for it, and no merging of the two */
    public static final String EMAIL_BELONGS_TO_ACCOUNT = "EMAIL_BELONGS_TO_ACCOUNT";
    /** Wrong, expired, used or burned code; one answer for all of them (as OTP_INVALID of accounts) */
    public static final String OTP_INVALID = "OTP_INVALID";
    public static final String PROFILE_TOKEN_INVALID = "PROFILE_TOKEN_INVALID";
    public static final String REFRESH_TOKEN_INVALID = "REFRESH_TOKEN_INVALID";
    public static final String GUEST_BLOCKED = "GUEST_BLOCKED";
    public static final String GUEST_NOT_FOUND = "GUEST_NOT_FOUND";
    public static final String CONSENT_REQUIRED = "CONSENT_REQUIRED";
    public static final String CAPTCHA_FAILED = "CAPTCHA_FAILED";

    public static ApplicationException emailBelongsToAccount() {
        return new ApplicationException("This email belongs to an account: sign in with the password instead",
                HttpStatus.CONFLICT, EMAIL_BELONGS_TO_ACCOUNT);
    }

    public static ApplicationException otpInvalid() {
        return new ApplicationException("Invalid or expired code", HttpStatus.BAD_REQUEST, OTP_INVALID);
    }

    public static ApplicationException profileTokenInvalid() {
        return new ApplicationException("The profile step has expired: ask for a new code", HttpStatus.UNAUTHORIZED,
                PROFILE_TOKEN_INVALID);
    }

    public static ApplicationException refreshTokenInvalid() {
        return new ApplicationException("Sign in again", HttpStatus.UNAUTHORIZED, REFRESH_TOKEN_INVALID);
    }

    public static ApplicationException blocked() {
        return new ApplicationException("This guest is blocked", HttpStatus.FORBIDDEN, GUEST_BLOCKED);
    }

    public static ApplicationException notFound() {
        return new ApplicationException("Guest not found", HttpStatus.NOT_FOUND, GUEST_NOT_FOUND);
    }

    public static ApplicationException consentRequired() {
        return new ApplicationException("Consent is required", HttpStatus.BAD_REQUEST, CONSENT_REQUIRED);
    }

    public static ApplicationException captchaFailed() {
        return new ApplicationException("The captcha was not passed", HttpStatus.BAD_REQUEST, CAPTCHA_FAILED);
    }
}
