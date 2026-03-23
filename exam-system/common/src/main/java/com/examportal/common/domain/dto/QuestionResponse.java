package com.examportal.common.domain.dto;

import com.examportal.common.domain.entity.QuestionType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Question Response DTO
 * Used for returning question details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuestionResponse {

    private Long id;
    private Long examId;
    private String questionText;
    private QuestionType questionType;
    private BigDecimal points;
    private Integer orderIndex;
    private Boolean allowMultipleAnswers;
    private Boolean isRequired;
    private String explanation; // Only shown after submission or to instructors
    private List<OptionResponse> options;
}
