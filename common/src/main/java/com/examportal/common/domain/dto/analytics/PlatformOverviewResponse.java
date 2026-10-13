package com.examportal.common.domain.dto.analytics;

import com.examportal.common.domain.dto.internal.UserStatsResponse;
import com.examportal.common.domain.entity.ExamStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Platform Overview Response DTO
 * Figures over all exams for ADMIN. The per-day lists cover the asked number of days in the exam
 * day zone; the overall score figures cover everything, over each participant's best completed
 * attempt per exam (sessions waiting for a grader left out). Percentages are 0..100.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformOverviewResponse {

    // When the figures were computed (UTC); they may be up to a minute old
    private LocalDateTime generatedAt;
    private int days;

    // Exams that are not deleted, per status; every status is present
    private Map<ExamStatus, Integer> examsByStatus;
    // Sessions that are IN_PROGRESS and not past their own end time
    private int activeNowInProgressSessions;
    private List<DayCount> sessionsPerDay;
    private List<DayCount> uniqueParticipantsPerDay;
    // Null while no attempt is fully graded
    private BigDecimal averagePercentageOverall;
    private BigDecimal passRateOverall;
    // Completed sessions that still wait for a grader
    private int pendingGradingTotal;
    // By sessions started within the asked days; at most 5
    private List<TopExam> topExamsBySessions;

    // False when auth-service could not be asked: users is null then, everything else is complete
    private boolean usersAvailable;
    private UserStatsResponse users;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DayCount {
        // yyyy-MM-dd, a calendar day in the exam day zone
        private String date;
        private int count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopExam {
        private Long examId;
        private String title;
        private ExamStatus status;
        private int sessionCount;
        private int participantCount;
    }
}
