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


    public static ExamException cannotModify() {
        return new ExamException("Cannot modify exam after it has started", HttpStatus.BAD_REQUEST, "EXAM_CANNOT_MODIFY");
    }

    public static ExamException exportTooLarge(int limit) {
        return new ExamException("More than " + limit + " rows to export; narrow the list with q or a filter",
                HttpStatus.UNPROCESSABLE_ENTITY, "RESULTS_EXPORT_TOO_LARGE");
    }

    /**
     * The exam has sessions (attempts): what decides their results (questions, options, points, the
     * answer key, the order and the duration) must stay as it was. Reschedule, audience, publish and
     * archive remain possible.
     */
    public static ExamException hasSessions() {
        return new ExamException("The exam has attempts: its questions, answers, points and duration can no longer be changed",
                HttpStatus.CONFLICT, "EXAM_HAS_SESSIONS");
    }

    /**
     * Reschedule of a finished or archived exam with attempts to a later start (SCHEDULED): that
     * status would open it for editing again
     */
    public static ExamException rescheduleHasSessions() {
        return new ExamException("A finished or archived exam with attempts cannot get a start time in the future; "
                + "reopen it with a start time now or in the past", HttpStatus.CONFLICT, "EXAM_HAS_SESSIONS");
    }

    // ==================== B12a: blocks, history, readiness ====================

    /**
     * A request without blocks (the older format) for an exam that has more than one: the questions
     * cannot be put into one block without losing the others
     */
    public static ExamException blocksRequired() {
        return new ExamException("This exam has more than one block: send the questions inside blocks",
                HttpStatus.BAD_REQUEST, "BLOCKS_REQUIRED");
    }

    /**
     * Block timers and breaks add up to more than the exam window (start..end)
     */
    public static ExamException durationExceedsWindow(int totalMinutes, long windowMinutes) {
        return (ExamException) new ExamException("The blocks take " + totalMinutes + " min with their breaks, the window is "
                + windowMinutes + " min", HttpStatus.BAD_REQUEST, "EXAM_DURATION_EXCEEDS_WINDOW")
                .withDetails(java.util.Map.of("totalMinutes", totalMinutes, "windowMinutes", windowMinutes));
    }

    /**
     * Block timers, a block without free navigation or returning to earlier blocks need the participant
     * side of blocks (B12b); until it is switched on such an exam cannot be published
     */
    public static ExamException featureNotAvailable(Object details) {
        return (ExamException) new ExamException("Block timers, fixed navigation and returning to earlier blocks are not "
                + "available yet: the exam cannot be published with them", HttpStatus.CONFLICT, "FEATURE_NOT_AVAILABLE")
                .withDetails(details);
    }

    /**
     * Publishing an exam the readiness check blocks; details lists what is missing
     */
    public static ExamException notReady(Object details) {
        return (ExamException) new ExamException("The exam is not ready to be published", HttpStatus.CONFLICT, "EXAM_NOT_READY")
                .withDetails(details);
    }

    /**
     * A sensitive change of a published exam without a reason, while REQUIRE_CHANGE_REASON_AFTER_PUBLISH is on
     */
    public static ExamException changeReasonRequired(Object details) {
        return (ExamException) new ExamException("A reason (changeReason, 3-500 characters) is required for this change "
                + "of a published exam", HttpStatus.BAD_REQUEST, "CHANGE_REASON_REQUIRED").withDetails(details);
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
