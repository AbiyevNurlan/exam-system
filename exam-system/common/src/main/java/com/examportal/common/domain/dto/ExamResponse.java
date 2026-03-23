package com.examportal.common.domain.dto;

import com.examportal.common.domain.entity.ExamStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Exam Response DTO
 * Used for returning exam details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExamResponse {

    private Long id;
    private String title;
    private String description;
    private Integer durationMinutes;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private ExamStatus status;
    private Integer totalQuestions;
    private BigDecimal passingScore;
    private Boolean shuffleQuestions;
    private Boolean shuffleOptions;
    private Boolean showResultsImmediately;
    private Integer maxAttempts;
    private Long categoryId;
    private String categoryName;
    private Long createdBy;
    private String creatorUsername;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<QuestionResponse> questions;
    
    // Computed fields for participants
    private Integer remainingAttempts;
    private Boolean canStart;
    private BigDecimal totalPoints;
}
