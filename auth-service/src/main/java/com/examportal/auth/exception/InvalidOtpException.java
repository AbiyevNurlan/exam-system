package com.examportal.auth.exception;

import com.examportal.common.exception.ApplicationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Exception thrown when a verification code is not accepted.
 * The message is always the same: wrong code, expired code, used code, too many attempts, unknown
 * email and already verified email cannot be told apart.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidOtpException extends ApplicationException {

    public InvalidOtpException() {
        super("Invalid or expired code", HttpStatus.BAD_REQUEST, "OTP_INVALID");
    }
}