package com.examportal.common.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Exam Result Response DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamResultResponse {
    
    private String sessionId;
    private Long examId;
    private String examTitle;
    private Integer totalQuestions;
    private Integer correctAnswers;
    private Integer incorrectAnswers;
    private Integer unansweredQuestions;
    private BigDecimal totalScore;
    private BigDecimal maxScore;
    private BigDecimal percentageScore;
    private Boolean passed;
    private BigDecimal passingScore;
    private LocalDateTime startTime;
    private LocalDateTime completedAt;
    private Long durationSeconds;
}
