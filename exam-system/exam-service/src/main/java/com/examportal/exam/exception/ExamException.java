package com.examportal.exam.exception;

import com.examportal.common.exception.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * Exam-specific exceptions
 */
public class ExamException extends ApplicationException {

    public ExamException(String message) {
        super(message, HttpStatus.BAD_REQUEST);
    }

    public ExamException(String message, HttpStatus status) {
        super(message, status);
    }

    public ExamException(String message, HttpStatus status, String errorCode) {
        super(message, status, errorCode);
    }

    public static ExamException notFound(Long examId) {
        return new ExamException("Exam not found with ID: " + examId, HttpStatus.NOT_FOUND, "EXAM_NOT_FOUND");
    }

    public static ExamException notOwner() {
        return new ExamException("You don't have permission to modify this exam", HttpStatus.FORBIDDEN, "EXAM_NOT_OWNER");
    }

    public static ExamException cannotModify() {
        return new ExamException("Cannot modify exam after it has started", HttpStatus.BAD_REQUEST, "EXAM_CANNOT_MODIFY");
    }

    public static ExamException cannotDelete() {
        return new ExamException("Cannot delete exam with existing attempts", HttpStatus.BAD_REQUEST, "EXAM_CANNOT_DELETE");
    }

    public static ExamException notActive() {
        return new ExamException("Exam is not currently active", HttpStatus.BAD_REQUEST, "EXAM_NOT_ACTIVE");
    }

    public static ExamException maxAttemptsReached() {
        return new ExamException("Maximum number of attempts reached", HttpStatus.BAD_REQUEST, "EXAM_MAX_ATTEMPTS");
    }

    public static ExamException sessionNotFound(String sessionId) {
        return new ExamException("Session not found: " + sessionId, HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND");
    }

    public static ExamException sessionExpired() {
        return new ExamException("Exam session has expired", HttpStatus.BAD_REQUEST, "SESSION_EXPIRED");
    }

    public static ExamException sessionAlreadyCompleted() {
        return new ExamException("Exam session is already completed", HttpStatus.BAD_REQUEST, "SESSION_COMPLETED");
    }

    public static ExamException activeSessionExists() {
        return new ExamException("You already have an active session for this exam", HttpStatus.BAD_REQUEST, "SESSION_ACTIVE_EXISTS");
    }

    public static ExamException schedulingConflict() {
        return new ExamException("Exam time conflicts with another exam", HttpStatus.CONFLICT, "EXAM_SCHEDULING_CONFLICT");
    }

    public static ExamException invalidTimeRange() {
        return new ExamException("End time must be after start time", HttpStatus.BAD_REQUEST, "EXAM_INVALID_TIME_RANGE");
    }

    public static ExamException questionNotFound(Long questionId) {
        return new ExamException("Question not found with ID: " + questionId, HttpStatus.NOT_FOUND, "QUESTION_NOT_FOUND");
    }
}
