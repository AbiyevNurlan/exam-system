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
 * Session Result Response DTO
 * Result of a completed session as seen by the participant. While the score is withheld
 * (resultsAvailable = false) only the summary fields are set. Never carries the answer key.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionResultResponse {

    private String sessionId;
    private Long examId;
    private SessionStatus status;
    private LocalDateTime submittedAt;
    private Boolean resultsAvailable;
    // PENDING = an open-ended answer waits for a grader: no score and no resultsAvailableAt until then
    private GradingStatus gradingStatus;
    private LocalDateTime resultsAvailableAt; // Only while the score is withheld

    private Integer totalQuestions;
    private Integer correctAnswers;
    private Integer incorrectAnswers;
    private Integer unansweredQuestions;
    private BigDecimal totalScore;
    private BigDecimal maxScore;
    private BigDecimal percentageScore;
    private Boolean passed;
    private BigDecimal passingScore;

    // B12a: the result per block, only while the result itself is shown, only when the exam shows a breakdown,
    // and only the blocks shown to participants: a hidden block is not here at all (score, percentage, answers)
    private java.util.List<BlockResultResponse> blocks;
}
