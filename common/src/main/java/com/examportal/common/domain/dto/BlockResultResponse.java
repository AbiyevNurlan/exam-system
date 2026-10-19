package com.examportal.common.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * The result of one block of a finished session (B12a). Participants get only the blocks the exam shows them
 * (exams.show_block_breakdown and exam_blocks.show_result_to_participant both true); a hidden block is not in
 * their answer at all. ADMIN and the owning INSTRUCTOR get every block, with showResultToParticipant.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BlockResultResponse {

    private Long blockId;
    private Integer position;
    private String title;
    private Integer totalQuestions;
    private Integer answeredQuestions;
    private Integer correctAnswers;
    private BigDecimal score;
    private BigDecimal maxScore;
    // score / maxScore * 100, two decimals; null for a block worth 0 points
    private BigDecimal percentage;
    // Only in the views of ADMIN and the owner
    private Boolean showResultToParticipant;
}
