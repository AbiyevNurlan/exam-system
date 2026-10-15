package com.examportal.common.domain.dto.grading;

import com.examportal.common.domain.entity.GradingStatus;
import com.examportal.common.domain.entity.SessionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Session Grading Response DTO
 * The open-ended answers of one session for the grader, with the session's current score.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionGradingResponse {

    private String sessionId;
    private Long examId;
    private UUID userId;
    private String participantName;
    private String email;
    // 7-digit participant code (display and search only, never security)
    private Integer participantCode;
    private SessionStatus status;
    private GradingStatus gradingStatus;
    private LocalDateTime submittedAt;
    // Provisional while gradingStatus is PENDING
    private BigDecimal score;
    private BigDecimal maxScore;
    private BigDecimal percentage;
    private Boolean passed;
    private List<GradingAnswerResponse> answers;
}
