package com.examportal.common.domain.dto;

import com.examportal.common.domain.dto.history.ExamHistoryDtos;
import com.examportal.common.domain.dto.media.MediaItemResponse;
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
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuestionResponse {

    private Long id;
    private Long examId;
    // B12a: the block the question belongs to
    private Long blockId;
    private String questionText;
    private QuestionType questionType;
    private BigDecimal points;
    private Integer orderIndex;
    private Boolean allowMultipleAnswers;
    private Boolean isRequired;
    private String explanation; // Only shown after submission or to instructors
    private List<OptionResponse> options;
    // MATCHING, for participants: the right sides, shuffled, each with an id of this session only
    private List<OptionResponse> matchOptions;
    // Attached media in display order; without URLs for participants
    private List<MediaItemResponse> media;
    // B12a, for ADMIN and the owner only: who changed the question last and when (its newest history row)
    private ExamHistoryDtos.Actor lastEditedBy;
    private java.time.LocalDateTime lastEditedAt;
}
