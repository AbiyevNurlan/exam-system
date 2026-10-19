package com.examportal.common.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * GET /api/me/stats: the figures of the signed-in user's own attempts, never anyone else's.
 *
 * Only results the user may already see count as graded (the visibility rule of the result endpoint):
 * the attempt is submitted, no open answer waits for a grader, and the exam shows results at once or
 * has ended. Per exam the best such attempt counts, as in the results list (bestOnly) and analytics.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MyStatsResponse {

    /** Different exams with at least one submitted attempt (also while its result is withheld) */
    private long examsTaken;

    /** Different exams with at least one submitted attempt whose result is final and visible */
    private long examsGraded;

    /** Average over those exams of the best visible percentage (2 decimals); null without any */
    private BigDecimal averagePercent;

    /** Highest visible percentage; null without any */
    private BigDecimal bestPercent;

    /** The most recently submitted attempt with a visible result; null without any */
    private LastResult lastResult;

    /** Submitted attempts whose open answers still wait for a grader */
    private long pendingGradingCount;

    /** B13, the dashboard of a teacher: whether the teacher has a group, an exam, a member in a group (ADMIN: anybody
     * on the platform; false for a participant), so a new teacher's screen can say where to start */
    private boolean hasGroups;
    private boolean hasExams;
    private boolean hasMembers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LastResult {
        private Long examId;
        private String examTitle;
        private BigDecimal percentage;
        private Boolean passed;
        private LocalDateTime submittedAt;
    }
}
