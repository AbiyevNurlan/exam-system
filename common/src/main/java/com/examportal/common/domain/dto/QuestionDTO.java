package com.examportal.common.domain.dto;

import com.examportal.common.domain.dto.media.QuestionMediaRef;
import com.examportal.common.domain.entity.QuestionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
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
    @Size(max = 5000, message = "Question text cannot exceed 5000 characters")
    private String questionText;

    @NotNull(message = "Question type is required")
    private QuestionType questionType;

    @NotNull(message = "Points are required")
    @DecimalMin(value = "0.01", message = "Points must be at least 0.01")
    @DecimalMax(value = "1000", message = "Points cannot exceed 1000")
    @Digits(integer = 4, fraction = 2, message = "Points allow at most 2 decimal places")
    private BigDecimal points;

    @NotNull(message = "Order index is required")
    @PositiveOrZero(message = "Order index must be non-negative")
    private Integer orderIndex;

    @Builder.Default
    private Boolean allowMultipleAnswers = false;

    @Builder.Default
    private Boolean isRequired = true;

    @Size(max = 5000, message = "Explanation cannot exceed 5000 characters")
    private String explanation;

    @Size(max = 10, message = "A question cannot have more than 10 options")
    @Valid
    private List<OptionDTO> options;

    // Up to 3 ACTIVE media of the editor (ADMIN: anyone's); maxPlays only for AUDIO/VIDEO
    @Size(max = 3, message = "A question cannot have more than 3 media")
    @Valid
    private List<QuestionMediaRef> media;
}
