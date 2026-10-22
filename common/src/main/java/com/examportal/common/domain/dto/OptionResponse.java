package com.examportal.common.domain.dto;

import com.examportal.common.domain.dto.media.MediaItemResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Option Response DTO
 * Used for returning question option details.
 * Note: isCorrect is hidden for participants during exam.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OptionResponse {

    private Long id;
    private Long questionId;
    private String optionText;
    // MATCHING: the right side of the pair (never sent to participants with the left side)
    private String matchText;
    private Integer orderIndex;
    private Boolean isCorrect; // Only shown after submission or to instructors
    // Attached image (at most one); without URL for participants
    private List<MediaItemResponse> media;
}
