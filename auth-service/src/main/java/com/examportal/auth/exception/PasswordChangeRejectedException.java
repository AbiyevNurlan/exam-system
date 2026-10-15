package com.examportal.auth.exception;

import com.examportal.common.exception.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * POST /auth/change-password refused: the current password is wrong, or the account cannot change
 * it (gone, deactivated). Always the same message and code, so nothing is learnt from the answer.
 * 400 and not 401: a 401 would make the frontend refresh the token and sign the user out.
 */
public class PasswordChangeRejectedException extends ApplicationException {

    public PasswordChangeRejectedException() {
        super("The password could not be changed", HttpStatus.BAD_REQUEST, "PASSWORD_CHANGE_REJECTED");
    }
}
