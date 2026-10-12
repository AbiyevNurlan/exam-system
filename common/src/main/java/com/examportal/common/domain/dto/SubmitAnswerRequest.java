package com.examportal.common.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Submit Answer Request DTO
 * Used for submitting answers during an exam.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitAnswerRequest {

    @NotNull(message = "Question ID is required")
    private Long questionId;

    private Long selectedOptionId;  // Legacy: treated as a one-element selectedOptionIds

    private List<Long> selectedOptionIds;  // Chosen options for choice questions
    
    private String textAnswer;
}
