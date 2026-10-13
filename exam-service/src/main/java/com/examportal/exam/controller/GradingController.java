package com.examportal.exam.controller;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.grading.GradeRequest;
import com.examportal.common.domain.dto.grading.GradingQueueRowResponse;
import com.examportal.common.domain.dto.grading.SessionGradingResponse;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.service.GradingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Grading Controller
 * Manual grading of open-ended answers: ADMIN every exam, INSTRUCTOR only the own ones.
 */
@Slf4j
@RestController
@RequestMapping("/api/exams")
@RequiredArgsConstructor
@Tag(name = "Grading", description = "Manual grading of open-ended answers")
@SecurityRequirement(name = "bearerAuth")
public class GradingController {

    private final GradingService gradingService;

    @GetMapping("/{examId}/grading")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Get the grading queue", description = "Completed sessions of the exam with open-ended answers, "
            + "oldest submission first. status: PENDING (default), COMPLETE or ALL. q: first name, last name, email prefix "
            + "or a user id (at least 2 characters).")
    public Mono<ApiResponse<PageResponse<GradingQueueRowResponse>>> getQueue(
            @PathVariable Long examId,
            @RequestParam(required = false) @Parameter(description = "PENDING (default), COMPLETE or ALL") String status,
            @RequestParam(required = false) @Parameter(description = "Participant name, email prefix or user id") String q,
            @RequestParam(defaultValue = "0") @Parameter(description = "Page number (0-indexed)") int page,
            @RequestParam(defaultValue = "20") @Parameter(description = "Page size (max 50)") int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        return Mono.defer(() -> gradingService.getQueue(examId, status, q, page, size, principal.getId(), principal.getRole()))
                .map(ApiResponse::success);
    }

    @GetMapping("/session/{sessionId}/grading")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Get the open-ended answers of a session", description = "The answers to open-ended questions "
            + "with their points, feedback, grader and gradeVersion, and the session's current score.")
    public Mono<ApiResponse<SessionGradingResponse>> getSessionGrading(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserPrincipal principal) {

        return gradingService.getSessionGrading(sessionId, principal.getId(), principal.getRole())
                .map(ApiResponse::success);
    }

    @PutMapping("/session/{sessionId}/grades")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Grade open-ended answers", description = "Body: [{answerId, points, feedback?, expectedVersion}] "
            + "(at most 50). All or nothing. points: 0 .. the question's points, at most 2 decimals. Only a COMPLETED session "
            + "(409 SESSION_NOT_COMPLETED). 400 GRADE_INVALID for a wrong item; 409 GRADE_CONFLICT when expectedVersion is "
            + "not the stored gradeVersion. The session's score, percentage and passed are recalculated; a grade can be changed later.")
    public Mono<ApiResponse<SessionGradingResponse>> grade(
            @PathVariable String sessionId,
            @RequestBody List<GradeRequest> grades,
            @AuthenticationPrincipal UserPrincipal principal) {

        log.info("Grading session: {} by user: {}", sessionId, principal.getId());

        return gradingService.grade(sessionId, grades, principal.getId(), principal.getRole())
                .map(result -> ApiResponse.success(result, "Grades saved successfully"));
    }
}
