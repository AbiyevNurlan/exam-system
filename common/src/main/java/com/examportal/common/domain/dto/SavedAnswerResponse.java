package com.examportal.common.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Saved Answer Response DTO
 * One answer a participant has stored in a session, as the participant gave it. Never carries
 * whether it is correct, points or anything else of the answer key.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SavedAnswerResponse {

    private Long questionId;

    // Chosen options; empty for a text answer
    private List<Long> selectedOptionIds;

    // Only for FILL_BLANK and OPEN_ENDED
    private String textAnswer;

    private LocalDateTime answeredAt;
}