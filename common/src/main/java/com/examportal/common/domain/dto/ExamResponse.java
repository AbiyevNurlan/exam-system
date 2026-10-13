package com.examportal.common.domain.dto;

import com.examportal.common.domain.entity.ExamAccessType;
import com.examportal.common.domain.entity.ExamStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Exam Response DTO
 * Used for returning exam details.
 */
@Data
@Builder(toBuilder = true)
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
    // Absent = no daily limit
    private Integer maxAttemptsPerDay;
    private Long categoryId;
    private String categoryName;
    private UUID createdBy;
    private String creatorUsername;
    private ExamAccessType accessType;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<QuestionResponse> questions;
    
    // Computed fields for participants
    // remainingAttempts: what can still be started now, so it respects the total and the daily limit
    private Integer remainingAttempts;
    // Absent = no daily limit
    private Integer remainingAttemptsToday;
    // Only when today's attempts are used up and a later day is still inside the exam window
    private LocalDateTime nextAttemptAt;
    private Boolean canStart;
    private BigDecimal totalPoints;
}
