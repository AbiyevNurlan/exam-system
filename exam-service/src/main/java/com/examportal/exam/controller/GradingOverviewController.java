package com.examportal.exam.controller;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.grading.PendingCountResponse;
import com.examportal.common.domain.dto.grading.PendingExamResponse;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.service.GradingOverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Grading Overview Controller
 * What waits for a grader over all exams: ADMIN every exam, INSTRUCTOR only the own ones.
 */
@RestController
@RequestMapping("/api/grading")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
@Tag(name = "Grading", description = "Manual grading of open-ended answers")
@SecurityRequirement(name = "bearerAuth")
public class GradingOverviewController {

    private final GradingOverviewService overviewService;

    @GetMapping("/pending-exams")
    @Operation(summary = "Exams waiting for a grader", description = "Exams with completed sessions whose open answers are "
            + "not graded yet: pendingSessionCount, pendingAnswerCount and oldestPendingAt, oldest waiting first, max 50 "
            + "per page. Cached for at most 10 seconds.")
    public Mono<ApiResponse<PageResponse<PendingExamResponse>>> pendingExams(
            @RequestParam(defaultValue = "0") @Parameter(description = "Page number (0-indexed)") int page,
            @RequestParam(defaultValue = "20") @Parameter(description = "Page size (max 50)") int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        return overviewService.pendingExams(principal.getId(), principal.getRole(), page, size)
                .map(ApiResponse::success);
    }

    @GetMapping("/pending-count")
    @Operation(summary = "How much waits for a grader", description = "Pending sessions and open answers over all exams "
            + "(an instructor: the own exams), for the menu badge. Cached for at most 10 seconds.")
    public Mono<ApiResponse<PendingCountResponse>> pendingCount(@AuthenticationPrincipal UserPrincipal principal) {
        return overviewService.pendingCount(principal.getId(), principal.getRole())
                .map(ApiResponse::success);
    }
}
