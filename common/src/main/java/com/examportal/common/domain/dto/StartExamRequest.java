package com.examportal.common.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Start Exam Request DTO
 * Used for starting an exam session.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartExamRequest {

    @NotNull(message = "Exam ID is required")
    private Long examId;

    private String ipAddress;
    private String browserInfo;
}
