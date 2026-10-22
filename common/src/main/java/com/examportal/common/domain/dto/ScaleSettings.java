package com.examportal.common.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The scale of a LINEAR_SCALE question ("Diapazon"), as in a linear scale of Google Forms: whole numbers from min
 * (0 or 1) to max (2..10), with an optional label under each end. The participant picks one number. The right
 * number is not here: like the accepted answers of FILL_BLANK it is the question's only option (option text = the
 * number), so it stays in the answer key and never reaches a participant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScaleSettings {

    public static final int MAX_LABEL = 100;

    @NotNull(message = "Scale minimum is required")
    @Min(value = 0, message = "Scale minimum must be 0 or 1")
    @Max(value = 1, message = "Scale minimum must be 0 or 1")
    private Integer min;

    @NotNull(message = "Scale maximum is required")
    @Min(value = 2, message = "Scale maximum must be between 2 and 10")
    @Max(value = 10, message = "Scale maximum must be between 2 and 10")
    private Integer max;

    @Size(max = MAX_LABEL, message = "A scale label cannot exceed 100 characters")
    private String minLabel;

    @Size(max = MAX_LABEL, message = "A scale label cannot exceed 100 characters")
    private String maxLabel;

    // The scale of stored columns, or null when the question has none (every type but LINEAR_SCALE)
    public static ScaleSettings of(Integer min, Integer max, String minLabel, String maxLabel) {
        return min == null || max == null ? null
                : ScaleSettings.builder().min(min).max(max).minLabel(minLabel).maxLabel(maxLabel).build();
    }
}
