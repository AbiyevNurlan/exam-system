package com.examportal.exam.exception;

import com.examportal.common.exception.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * Media Exception
 * Errors of the question media endpoints and of the object storage behind them.
 */
public class MediaException extends ApplicationException {

    public MediaException(String message, HttpStatus status, String errorCode) {
        super(message, status, errorCode);
    }

    public MediaException(String message, HttpStatus status, String errorCode, Throwable cause) {
        super(message, status, errorCode, cause);
    }

    public static MediaException storageUnavailable(Throwable cause) {
        return new MediaException("Media storage is currently unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                "MEDIA_STORAGE_UNAVAILABLE", cause);
    }
}
