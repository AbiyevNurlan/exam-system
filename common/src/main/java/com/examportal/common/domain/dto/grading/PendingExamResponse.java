package com.examportal.common.domain.dto.grading;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Pending Exam Response DTO
 * An exam with completed sessions whose open answers wait for a grader: GET /api/grading/pending-exams
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingExamResponse {

    private Long examId;
    private String title;
    // Completed sessions with grading status PENDING
    private long pendingSessionCount;
    // Open answers of those sessions that still need a grade
    private long pendingAnswerCount;
    // Submission time of the oldest of those sessions
    private LocalDateTime oldestPendingAt;
}
