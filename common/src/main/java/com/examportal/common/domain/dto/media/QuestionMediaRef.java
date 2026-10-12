package com.examportal.common.domain.dto.media;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Question Media Reference DTO
 * One media attached to a question in an exam create/update request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionMediaRef {

    @NotNull(message = "Media id is required")
    private UUID mediaId;

    // Display order within the question; defaults to the place in the list
    @PositiveOrZero(message = "Position must be non-negative")
    @Max(value = 100, message = "Position cannot exceed 100")
    private Integer position;

    // AUDIO/VIDEO only: how often a participant may load the media in one session; empty = unlimited
    @Min(value = 1, message = "Max plays must be at least 1")
    @Max(value = 100, message = "Max plays cannot exceed 100")
    private Integer maxPlays;
}