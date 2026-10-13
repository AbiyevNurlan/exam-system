package com.examportal.common.domain.dto.analytics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Exam Analytics Overview Response DTO
 * Figures of one exam. Counts of sessions are over all sessions; everything about scores is over
 * each participant's best completed attempt, leaving out sessions that still wait for a grader.
 * Percentages and passRate are 0..100.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamAnalyticsOverviewResponse {

    private Long examId;
    // When the figures were computed (UTC); they may be up to a minute old
    private LocalDateTime generatedAt;

    // Participants with a best completed attempt that is fully graded; the score distribution adds up to this
    private int participantCount;
    // Different users with at least one session of any status
    private int totalParticipantCount;
    private int sessionCount;
    private int completedCount;
    private int inProgressCount;
    // Completed sessions that still wait for a grader (not in the score figures)
    private int pendingGradingCount;
    // Completed sessions submitted at or after their own end time: closed by the clock, not by the participant
    private Integer expiredOrAutoSubmittedCount;

    // From start to submission of the best attempts; null when there is none
    private Long averageDurationSeconds;
    private BigDecimal averagePercentage;
    private BigDecimal medianPercentage;
    private BigDecimal passRate;

    // Ten buckets: 0-10, 10-20, ... 90-100 (100 belongs to the last one)
    private List<ScoreBucket> scoreDistribution;
    private Attempts attemptsPerParticipant;
    // The last 60 days in the exam day zone, oldest first, days without a session included
    private List<DayCount> sessionsPerDay;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScoreBucket {
        private int from;
        private int to;
        private String label;
        private int count;
    }

    /**
     * How many participants have one, two, or three and more sessions for the exam
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Attempts {
        private int one;
        private int two;
        private int threeOrMore;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DayCount {
        // yyyy-MM-dd, a calendar day in the exam day zone
        private String date;
        private int count;
    }
}
