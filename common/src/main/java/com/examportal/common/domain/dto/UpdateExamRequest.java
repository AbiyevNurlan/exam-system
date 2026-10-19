package com.examportal.common.domain.dto;

import com.examportal.common.domain.entity.ExamAccessType;
import com.examportal.common.domain.entity.TimingMode;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
 * Update Exam Request DTO
 * Used for updating an existing exam.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateExamRequest {

    @NotNull(message = "Exam ID is required")
    private Long id;

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
    private LocalDateTime startTime;

    @NotNull(message = "End time is required")
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

    // Sessions one user may open per calendar day. Omitted = the exam keeps its daily limit;
    // an explicit null removes it.
    @Min(value = 1, message = "Max attempts per day must be at least 1")
    @Max(value = 100, message = "Max attempts per day cannot exceed 100")
    private Integer maxAttemptsPerDay;

    // True once maxAttemptsPerDay was given (also as null): tells "omitted" from "remove the limit"
    @JsonIgnore
    private boolean maxAttemptsPerDayPresent;

    public void setMaxAttemptsPerDay(Integer maxAttemptsPerDay) {
        this.maxAttemptsPerDay = maxAttemptsPerDay;
        this.maxAttemptsPerDayPresent = true;
    }

    @DecimalMin(value = "0.0", message = "Passing score must be non-negative")
    @DecimalMax(value = "100.0", message = "Passing score cannot exceed 100")
    private BigDecimal passingScore;

    private Long categoryId;

    // Who may take the exam. Create: PUBLIC when omitted. Update: omitted = audience unchanged.
    private ExamAccessType accessType;

    // Audience groups, required (at least one) when accessType is GROUP; ignored for PUBLIC
    @Size(max = 50, message = "An exam can have at most 50 audience groups")
    private List<@NotNull(message = "Group id must not be null") Long> groupIds;

    // The questions of an exam without blocks (the format before B12a): the exam's only block. Either
    // questions or blocks; at least one question in all (ExamValidator). Question and option ids of this
    // exam keep their rows (changed in place); without an id a question or option is new.
    @Size(max = 200, message = "An exam cannot have more than 200 questions")
    @Valid
    private List<QuestionDTO> questions;

    // B12a: 1..20 blocks in order, each with its questions; a block id of this exam keeps the block
    @Size(max = 20, message = "An exam has at most 20 blocks")
    @Valid
    private List<ExamBlockDTO> blocks;

    // B12a: why (optional, 3-500 characters), written to the exam's history; required for a sensitive change of a
    // published exam only when REQUIRE_CHANGE_REASON_AFTER_PUBLISH is on
    private String changeReason;

    // Omitted = unchanged
    private TimingMode timingMode;
    private Boolean allowReturnToPreviousBlocks;
    private Boolean allowSkipBreak;
    private Boolean showBlockBreakdown;
}
