package com.examportal.common.domain.dto.grading;

import com.examportal.common.domain.entity.GradingStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Grading Queue Row Response DTO
 * One completed session in the grading queue of an exam.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GradingQueueRowResponse {

    private String sessionId;
    private UUID userId;
    private String participantName;
    private String email;
    // Which of the participant's sessions for this exam this is (1 = the first)
    private int attemptNumber;
    private LocalDateTime submittedAt;
    // Open-ended answers with text
    private int openAnswerCount;
    // Of those, still waiting for a grader
    private int pendingCount;
    // Of those, graded by a person
    private int gradedCount;
    private GradingStatus gradingStatus;
}
