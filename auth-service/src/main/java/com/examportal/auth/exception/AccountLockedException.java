package com.examportal.auth.exception;

import com.examportal.common.exception.ApplicationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Exception thrown when a login is attempted on a locked account
 */
@ResponseStatus(HttpStatus.LOCKED)
public class AccountLockedException extends ApplicationException {

    public AccountLockedException() {
        super("Account is locked. Try again later or contact support.", HttpStatus.LOCKED, "ACCOUNT_LOCKED");
    }
}
