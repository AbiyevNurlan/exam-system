package com.examportal.common.domain.dto.grading;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Grading Answer Response DTO
 * One answer to an open-ended question as the grader sees it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GradingAnswerResponse {

    private Long answerId;
    private Long questionId;
    private String questionText;
    private BigDecimal maxPoints;
    private String textAnswer;
    private BigDecimal pointsEarned;
    private String feedback;
    // The grader's name; gradedById is always set once graded, the name only when it could be loaded
    private String gradedBy;
    private UUID gradedById;
    private LocalDateTime gradedAt;
    // Send it back as expectedVersion when grading
    private Integer gradeVersion;
    private boolean needsManualReview;
}
