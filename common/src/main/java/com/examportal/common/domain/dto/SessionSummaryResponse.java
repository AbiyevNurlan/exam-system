package com.examportal.common.domain.dto;

import com.examportal.common.domain.entity.GradingStatus;
import com.examportal.common.domain.entity.RiskLevel;
import com.examportal.common.domain.entity.SessionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Session Summary Response DTO
 * One row of an exam's results list for the instructor/admin.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionSummaryResponse {

    private String sessionId;
    private UUID userId;
    // Null when the user is unknown to auth-service or it could not be reached
    private String participantName;
    private String email;
    // Which of the participant's sessions for this exam this is (1 = the first)
    private Integer attemptNumber;
    private SessionStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime submittedAt;
    // From start to submission; null while in progress
    private Long durationSeconds;
    private BigDecimal score;
    private BigDecimal maxScore;
    private BigDecimal percentage;
    private Boolean passed;
    // PENDING: score, percentage and passed are provisional until the open-ended answers are graded
    private GradingStatus gradingStatus;
    private Integer violationCount;
    private RiskLevel riskLevel;
}
