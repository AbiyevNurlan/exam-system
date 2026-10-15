package com.examportal.common.domain.dto.monitoring;

import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.entity.FraudEventType;
import com.examportal.common.domain.entity.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Exam Monitoring Response DTO
 * The running sessions of one exam for its managers. The figures at the top are over every running
 * session of the exam, whatever the search or the risk filter of the page. Carries no answer and
 * no answer key: only how far each participant is.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamMonitoringResponse {

    private Long examId;
    private String title;
    private int inProgressCount;
    private long violationCount;
    // Every level is present
    private Map<RiskLevel, Integer> riskCounts;
    // Active questions of the exam
    private int totalQuestions;
    // When these figures were read (UTC); they may come from a cache that is a few seconds old
    private LocalDateTime generatedAt;
    private PageResponse<Session> sessions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Session {
        private String sessionId;
        private UUID userId;
        private String participantName;
        private String email;
        // 7-digit participant code (display and search only, never security)
        private Integer participantCode;
        // Which of the participant's sessions for this exam this is (1 = the first)
        private int attemptNumber;
        private LocalDateTime startedAt;
        // The session's own end time
        private LocalDateTime deadline;
        // Questions with a non-empty answer so far
        private int answeredCount;
        private int totalQuestions;
        private int violationCount;
        // Only the types that occurred
        private Map<FraudEventType, Integer> countsByType;
        private int riskScore;
        private RiskLevel riskLevel;
        private LocalDateTime lastEventAt;
        private FraudEventType lastEventType;
    }
}
