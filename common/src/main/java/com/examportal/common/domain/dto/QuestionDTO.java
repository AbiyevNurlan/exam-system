package com.examportal.common.domain.dto;

import com.examportal.common.domain.entity.QuestionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Question DTO
 * Used for creating and updating questions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionDTO {

    private Long id;

    @NotBlank(message = "Question text is required")
    private String questionText;

    @NotNull(message = "Question type is required")
    private QuestionType questionType;

    @NotNull(message = "Points are required")
    @Positive(message = "Points must be positive")
    private BigDecimal points;

    @NotNull(message = "Order index is required")
    @PositiveOrZero(message = "Order index must be non-negative")
    private Integer orderIndex;

    @Builder.Default
    private Boolean allowMultipleAnswers = false;

    @Builder.Default
    private Boolean isRequired = true;

    private String explanation;

    @Valid
    private List<OptionDTO> options;
}
