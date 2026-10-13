package com.examportal.common.domain.dto;

import com.examportal.common.domain.entity.ExamAccessType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Create Exam Request DTO
 * Used for creating a new exam.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateExamRequest {

    @NotBlank(message = "Title is required")
    @Size(min = 3, max = 255, message = "Title must be between 3 and 255 characters")
    private String title;

    @Size(max = 5000, message = "Description cannot exceed 5000 characters")
    private String description;

    @NotNull(message = "Duration is required")
    @Min(value = 1, message = "Duration must be at least 1 minute")
    @Max(value = 480, message = "Duration cannot exceed 8 hours (480 minutes)")
    private Integer durationMinutes;

    @NotNull(message = "Start time is required")
    @Future(message = "Start time must be in the future")
    private LocalDateTime startTime;

    @NotNull(message = "End time is required")
    @Future(message = "End time must be in the future")
    private LocalDateTime endTime;

    @Builder.Default
    private Boolean shuffleQuestions = true;

    @Builder.Default
    private Boolean shuffleOptions = true;

    @Builder.Default
    private Boolean showResultsImmediately = false;

    @Builder.Default
    @Min(value = 1, message = "Max attempts must be at least 1")
    @Max(value = 100, message = "Max attempts cannot exceed 100")
    private Integer maxAttempts = 1;

    // Sessions one user may open per calendar day; omitted or null = no daily limit
    @Min(value = 1, message = "Max attempts per day must be at least 1")
    @Max(value = 100, message = "Max attempts per day cannot exceed 100")
    private Integer maxAttemptsPerDay;

    @DecimalMin(value = "0.0", message = "Passing score must be non-negative")
    @DecimalMax(value = "100.0", message = "Passing score cannot exceed 100")
    private BigDecimal passingScore;

    private Long categoryId;

    // Who may take the exam. Create: PUBLIC when omitted. Update: omitted = audience unchanged.
    private ExamAccessType accessType;

    // Audience groups, required (at least one) when accessType is GROUP; ignored for PUBLIC
    @Size(max = 50, message = "An exam can have at most 50 audience groups")
    private List<@NotNull(message = "Group id must not be null") Long> groupIds;

    @NotEmpty(message = "At least one question is required")
    @Size(max = 200, message = "An exam cannot have more than 200 questions")
    @Valid
    private List<QuestionDTO> questions;
}
