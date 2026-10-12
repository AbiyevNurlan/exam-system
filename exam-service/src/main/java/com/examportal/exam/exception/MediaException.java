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

    public static MediaException notFound() {
        return new MediaException("Media not found", HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND");
    }

    public static MediaException typeNotAllowed() {
        return new MediaException("This file type is not allowed for this kind of media", HttpStatus.BAD_REQUEST,
                "MEDIA_TYPE_NOT_ALLOWED");
    }

    public static MediaException tooLarge(long maxBytes) {
        return new MediaException("The file is larger than the limit of " + maxBytes + " bytes", HttpStatus.BAD_REQUEST,
                "MEDIA_TOO_LARGE");
    }

    public static MediaException pendingLimit(int maxPending) {
        return new MediaException("Too many unconfirmed uploads (limit " + maxPending + "); confirm or delete them first",
                HttpStatus.CONFLICT, "MEDIA_PENDING_LIMIT");
    }

    public static MediaException notUploaded() {
        return new MediaException("The file has not been uploaded yet", HttpStatus.CONFLICT, "MEDIA_NOT_UPLOADED");
    }

    public static MediaException invalid() {
        return new MediaException("The uploaded file does not match the declared type or size", HttpStatus.BAD_REQUEST,
                "MEDIA_INVALID");
    }

    public static MediaException notAttachable(String reason) {
        return new MediaException("Media cannot be attached: " + reason, HttpStatus.BAD_REQUEST, "MEDIA_NOT_ATTACHABLE");
    }

    public static MediaException inUse() {
        return new MediaException("The media is used by a published exam", HttpStatus.CONFLICT, "MEDIA_IN_USE");
    }

    public static MediaException storageUnavailable(Throwable cause) {
        return new MediaException("Media storage is currently unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                "MEDIA_STORAGE_UNAVAILABLE", cause);
    }
}
