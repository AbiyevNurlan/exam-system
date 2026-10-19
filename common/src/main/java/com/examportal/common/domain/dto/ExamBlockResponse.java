package com.examportal.common.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One block of an exam in a response (B12a). The questions themselves stay in ExamResponse.questions
 * (flat, in order, each with its blockId); questionIds gives the block's questions in order.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExamBlockResponse {

    private Long id;
    private Integer position;
    private String title;
    private String instructions;
    private Integer timeLimitMinutes;
    private Integer breakAfterMinutes;
    private Boolean freeNavigation;
    private Boolean shuffleQuestions;
    private Boolean showResultToParticipant;
    private Integer questionCount;
    private List<Long> questionIds;
}
