package com.examportal.common.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

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
    @Size(max = 1000, message = "Option text cannot exceed 1000 characters")
    private String optionText;

    // MATCHING: the right side of the pair
    @Size(max = 1000, message = "Match text cannot exceed 1000 characters")
    private String matchText;

    @Builder.Default
    private Boolean isCorrect = false;

    @NotNull(message = "Order index is required")
    @PositiveOrZero(message = "Order index must be non-negative")
    private Integer orderIndex;

    // At most one ACTIVE image of the editor (ADMIN: anyone's). Its alt text is visible to
    // participants and must not reveal the answer.
    @Size(max = 1, message = "An option cannot have more than 1 media")
    private List<@NotNull(message = "Media id must not be null") UUID> mediaIds;
}
