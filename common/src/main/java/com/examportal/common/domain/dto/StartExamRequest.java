package com.examportal.common.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

    @Size(max = 50, message = "IP address cannot exceed 50 characters")
    private String ipAddress;
    @Size(max = 1000, message = "Browser info cannot exceed 1000 characters")
    private String browserInfo;
}
