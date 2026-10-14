package com.examportal.common.domain.dto.grading;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Pending Count Response DTO
 * What waits for a grader, for the badge of the menu: GET /api/grading/pending-count
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingCountResponse {

    // Completed sessions with grading status PENDING
    private long sessions;
    // Open answers of those sessions that still need a grade
    private long answers;
}
