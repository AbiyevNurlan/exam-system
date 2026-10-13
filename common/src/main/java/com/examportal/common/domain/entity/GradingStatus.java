package com.examportal.common.domain.entity;

/**
 * Grading Status Enumeration
 * Whether a session waits for a person to grade its open-ended answers (exam_sessions.grading_status).
 */
public enum GradingStatus {
    // Nothing in the session needs a grader (also every session in progress)
    NOT_REQUIRED,
    // Completed, at least one answer waits for a grader: the score is not final
    PENDING,
    // Every answer that needed a grader has been graded
    COMPLETE
}
