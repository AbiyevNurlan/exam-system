package com.examportal.auth.exception;

import com.examportal.common.exception.ApplicationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * B13: the temporary password an administrator set (7 days) has run out; the administrator gives a new one
 * (POST /api/admin/instructors/{id}/reset-password). Raised only after the password was accepted, so it tells
 * nothing to someone who does not know it.
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class TemporaryPasswordExpiredException extends ApplicationException {

    public TemporaryPasswordExpiredException() {
        super("The temporary password has expired; ask an administrator for a new one", HttpStatus.UNAUTHORIZED,
                "TEMPORARY_PASSWORD_EXPIRED");
    }
}
