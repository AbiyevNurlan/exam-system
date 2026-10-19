package com.examportal.common.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One block of an exam in a create or update request (B12a). The order of the blocks in the request
 * is their order; position, when given, must be that order (1..n). id: a block of this exam keeps its
 * row and id, without one it is new. A field left out takes its default (title "Əsas blok", no own
 * time limit, no break, free navigation, the exam's shuffle setting, result shown to the participant).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamBlockDTO {

    private Long id;

    @Min(value = 1, message = "Block position starts at 1")
    @Max(value = 20, message = "An exam has at most 20 blocks")
    private Integer position;

    @Size(max = 200, message = "Block title cannot exceed 200 characters")
    private String title;

    @Size(max = 5000, message = "Block instructions cannot exceed 5000 characters")
    private String instructions;

    // Required in BLOCK_TIMERS mode
    @Min(value = 1, message = "Block time limit must be at least 1 minute")
    @Max(value = 480, message = "Block time limit cannot exceed 480 minutes")
    private Integer timeLimitMinutes;

    // Pause before the next block; ignored (0) for the last block
    @Min(value = 0, message = "Break cannot be negative")
    @Max(value = 120, message = "Break cannot exceed 120 minutes")
    private Integer breakAfterMinutes;

    private Boolean freeNavigation;

    private Boolean shuffleQuestions;

    // false: the participant never gets this block's score, percentage or answers
    private Boolean showResultToParticipant;

    @Size(max = 200, message = "A block cannot have more than 200 questions")
    @Valid
    private List<QuestionDTO> questions;
}
