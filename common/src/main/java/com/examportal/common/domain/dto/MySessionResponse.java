package com.examportal.common.domain.dto;

import com.examportal.common.domain.entity.GradingStatus;
import com.examportal.common.domain.entity.SessionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * My Session Response DTO
 * One of the caller's own exam sessions in a list. The score fields are set only when
 * resultsAvailable is true, by the same rule as the result endpoint (SessionResultResponse).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MySessionResponse {

    private String sessionId;
    private Long examId;
    private String examTitle;
    private SessionStatus status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime submittedAt;

    private Boolean resultsAvailable;
    // PENDING = an open-ended answer waits for a grader: no score and no resultsAvailableAt until then
    private GradingStatus gradingStatus;
    private LocalDateTime resultsAvailableAt; // Only while the score of a completed session is withheld

    private BigDecimal totalScore;
    private BigDecimal maxScore;
    private BigDecimal percentageScore;
    private Boolean passed;
}