package com.examportal.exam.exception;

import com.examportal.common.exception.ApplicationException;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Map;

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

    public static ExamException exportTooLarge(int limit) {
        return new ExamException("More than " + limit + " rows to export; narrow the list with q or a filter",
                HttpStatus.UNPROCESSABLE_ENTITY, "RESULTS_EXPORT_TOO_LARGE");
    }

    public static ExamException cannotDelete() {
        return new ExamException("Cannot delete an exam that has attempts; archive it instead",
                HttpStatus.CONFLICT, "EXAM_CANNOT_DELETE");
    }

    public static ExamException cannotReschedule() {
        return new ExamException("Only a published exam can be rescheduled; edit a draft instead",
                HttpStatus.BAD_REQUEST, "EXAM_CANNOT_RESCHEDULE");
    }

    public static ExamException endTimeInPast() {
        return new ExamException("End time must be in the future", HttpStatus.BAD_REQUEST, "EXAM_END_TIME_PAST");
    }

    public static ExamException notActive() {
        return new ExamException("Exam is not currently active", HttpStatus.BAD_REQUEST, "EXAM_NOT_ACTIVE");
    }

    public static ExamException maxAttemptsReached() {
        return new ExamException("Maximum number of attempts reached", HttpStatus.CONFLICT, "MAX_ATTEMPTS_REACHED");
    }

    /**
     * Today's attempts are used up while the total limit still has room. error.details.nextAvailableAt
     * is the start of the next day (UTC).
     */
    public static ExamException dailyAttemptsReached(LocalDateTime nextAvailableAt) {
        ExamException exception = new ExamException("Daily number of attempts reached", HttpStatus.CONFLICT,
                "DAILY_ATTEMPTS_REACHED");
        exception.withDetails(Map.of("nextAvailableAt", nextAvailableAt));
        return exception;
    }

    public static ExamException sessionNotFound(String sessionId) {
        return new ExamException("Session not found: " + sessionId, HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND");
    }

    public static ExamException sessionExpired() {
        return new ExamException("Exam session has expired", HttpStatus.GONE, "SESSION_EXPIRED");
    }

    public static ExamException sessionAlreadyCompleted() {
        return new ExamException("Exam session is already completed", HttpStatus.CONFLICT, "SESSION_COMPLETED");
    }

    public static ExamException invalidTimeRange() {
        return new ExamException("End time must be after start time", HttpStatus.BAD_REQUEST, "EXAM_INVALID_TIME_RANGE");
    }

    public static ExamException questionNotFound(Long questionId) {
        return new ExamException("Question not found with ID: " + questionId, HttpStatus.NOT_FOUND, "QUESTION_NOT_FOUND");
    }

    public static ExamException sessionNotCompleted() {
        return new ExamException("Exam session is not completed yet", HttpStatus.CONFLICT, "SESSION_NOT_COMPLETED");
    }

    public static ExamException gradeInvalid(String reason) {
        return new ExamException("The grade cannot be stored: " + reason, HttpStatus.BAD_REQUEST, "GRADE_INVALID");
    }

    /**
     * The answer was graded by someone else since the grader looked at it; nothing was written.
     * error.details carries the answer and the version that is stored now.
     */
    public static ExamException gradeConflict(Long answerId, int currentVersion) {
        ExamException exception = new ExamException("The answer was graded in the meantime; reload and grade again",
                HttpStatus.CONFLICT, "GRADE_CONFLICT");
        exception.withDetails(Map.of("answerId", answerId, "currentVersion", currentVersion));
        return exception;
    }

    public static ExamException windowEnded() {
        return new ExamException("The exam's end time has passed; give it a new end time with reschedule (a draft: edit it)",
                HttpStatus.BAD_REQUEST, "EXAM_WINDOW_ENDED");
    }

    public static ExamException selectionRequired() {
        return new ExamException("At least one option must be selected", HttpStatus.BAD_REQUEST, "ANSWER_SELECTION_REQUIRED");
    }

    public static ExamException singleSelectionOnly() {
        return new ExamException("This question accepts exactly one option", HttpStatus.BAD_REQUEST, "ANSWER_SINGLE_SELECTION_ONLY");
    }

    public static ExamException invalidOption() {
        return new ExamException("Selected option does not belong to this question", HttpStatus.BAD_REQUEST, "ANSWER_INVALID_OPTION");
    }
}
