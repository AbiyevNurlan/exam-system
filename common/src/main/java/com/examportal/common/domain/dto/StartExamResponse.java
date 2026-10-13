package com.examportal.common.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Start Exam Response DTO
 * Returned when an exam session is started.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StartExamResponse {

    private String sessionId;
    private Long examId;
    private String examTitle;
    private LocalDateTime startTime;
    private LocalDateTime endTime; // Calculated deadline
    private Integer durationMinutes;
    private Integer totalQuestions;
    private Integer currentBatch;
    private Integer totalBatches;
    private List<QuestionResponse> questions; // First batch of questions

    // What the participant has already answered in this session (empty for a new one); no answer key
    private List<SavedAnswerResponse> savedAnswers;
}
