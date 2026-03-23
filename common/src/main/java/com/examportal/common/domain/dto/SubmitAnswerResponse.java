package com.examportal.common.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Submit Answer Response DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitAnswerResponse {
    
    private Boolean success;
    private Long questionId;
    private Integer answeredQuestions;
    private String message;
}
