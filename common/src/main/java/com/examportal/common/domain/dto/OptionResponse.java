package com.examportal.common.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Option Response DTO
 * Used for returning question option details.
 * Note: isCorrect is hidden for participants during exam.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OptionResponse {

    private Long id;
    private Long questionId;
    private String optionText;
    private Integer orderIndex;
    private Boolean isCorrect; // Only shown after submission or to instructors
}
