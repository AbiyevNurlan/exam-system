package com.examportal.common.domain.dto.grading;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Grade Request DTO
 * The points a grader gives to one open-ended answer. A request carries up to 50 of these and is
 * applied as a whole or not at all.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GradeRequest {

    public static final int MAX_ITEMS = 50;
    public static final int MAX_FEEDBACK_LENGTH = 2000;

    private Long answerId;

    // 0 .. the question's points, at most 2 decimals
    private BigDecimal points;

    // Shown to the participant with the result; optional, at most 2000 characters
    private String feedback;

    // gradeVersion of the answer as the grader saw it; a different stored version is 409 GRADE_CONFLICT
    private Integer expectedVersion;
}
