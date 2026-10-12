package com.examportal.common.domain.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Start Exam Request DTO
 * Used for starting an exam session. The whole body is optional.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartExamRequest {

    // Optional and ignored: the exam is identified by the path (/api/exams/{id}/start)
    private Long examId;

    @Size(max = 50, message = "IP address cannot exceed 50 characters")
    private String ipAddress;
    @Size(max = 1000, message = "Browser info cannot exceed 1000 characters")
    private String browserInfo;
}
