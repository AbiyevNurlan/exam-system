package com.examportal.common.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Option DTO
 * Used for creating and updating question options.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptionDTO {

    private Long id;

    @NotBlank(message = "Option text is required")
    private String optionText;

    @Builder.Default
    private Boolean isCorrect = false;

    @NotNull(message = "Order index is required")
    @PositiveOrZero(message = "Order index must be non-negative")
    private Integer orderIndex;
}
