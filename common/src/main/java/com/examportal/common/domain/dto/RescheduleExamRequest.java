package com.examportal.common.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Reschedule Exam Request DTO
 * Opens a published exam again with a new time window. Only endTime is required; whatever is
 * omitted stays as it is.
 */
@Data
@NoArgsConstructor
public class RescheduleExamRequest {

    // Omitted = the exam keeps its start time
    private LocalDateTime startTime;

    @NotNull(message = "End time is required")
    @Future(message = "End time must be in the future")
    private LocalDateTime endTime;

    @Min(value = 1, message = "Max attempts must be at least 1")
    @Max(value = 100, message = "Max attempts cannot exceed 100")
    private Integer maxAttempts;

    // Omitted = the exam keeps its daily limit; an explicit null removes it
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
}
