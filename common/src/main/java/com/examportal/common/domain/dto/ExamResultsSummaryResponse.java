package com.examportal.common.domain.dto;

import com.examportal.common.domain.entity.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Exam Results Summary Response DTO
 * Figures of one exam. The counts are over all sessions; everything about scores and violations is
 * over each participant's best completed attempt, leaving out sessions that still wait for a grader
 * (those are counted in pendingGradingCount only).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamResultsSummaryResponse {

    // Different users with at least one session
    private long participantCount;
    private long sessionCount;
    private long completedCount;
    private long inProgressCount;
    // Completed sessions whose open-ended answers still wait for a grader
    private long pendingGradingCount;

    // Participants with a best completed attempt that is fully graded: the base of what follows
    private long rankedParticipantCount;
    // Null when nobody is ranked yet
    private BigDecimal averagePercentage;
    // Share of ranked participants who passed, 0..100; null when nobody is ranked yet
    private BigDecimal passRate;
    // Ranked participants whose best attempt has no violation
    private long cleanParticipantCount;

    private List<Scorer> topScorers;
    private List<Scorer> lowestScorers;
    // Only participants with at least one violation
    private List<Scorer> mostViolations;
    // The fewest violations; among equals the higher score first
    private List<Scorer> fewestViolations;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Scorer {
        private UUID userId;
        private String participantName;
        // 7-digit participant code (display and search only, never security)
        private Integer participantCode;
        private String sessionId;
        private BigDecimal percentage;
        private BigDecimal score;
        private BigDecimal maxScore;
        private int violationCount;
        private RiskLevel riskLevel;
    }
}
