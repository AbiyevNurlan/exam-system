package com.examportal.common.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

    @Size(max = 10, message = "At most 10 options can be selected")
    private List<Long> selectedOptionIds;  // Chosen options for choice questions
    
    @Size(max = 10000, message = "Text answer cannot exceed 10000 characters")
    private String textAnswer;
}
