package com.examportal.exam.controller;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.monitoring.ActiveExamResponse;
import com.examportal.common.domain.dto.monitoring.ExamMonitoringResponse;
import com.examportal.common.domain.dto.monitoring.ViolationResponse;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.service.MonitoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Monitoring Controller
 * Live monitoring of running exams: ADMIN every exam, INSTRUCTOR only the own ones. Read only.
 */
@RestController
@RequestMapping("/api/exams")
@RequiredArgsConstructor
@Tag(name = "Monitoring", description = "Live monitoring of running exam sessions")
@SecurityRequirement(name = "bearerAuth")
public class MonitoringController {

    private final MonitoringService monitoringService;

    @GetMapping("/monitoring/active")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Get the exams being taken now", description = "Exams with at least one session that is IN_PROGRESS and "
            + "not past its own end time, the highest risk level first, then the most running sessions. riskCounts: running "
            + "sessions per level. Cached for 3 seconds.")
    public Mono<ApiResponse<List<ActiveExamResponse>>> getActiveExams(@AuthenticationPrincipal UserPrincipal principal) {
        return monitoringService.getActiveExams(principal.getId(), principal.getRole())
                .map(ApiResponse::success);
    }

    @GetMapping("/{examId}/monitoring")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Get the running sessions of an exam", description = "Running sessions with progress (answeredCount of "
            + "totalQuestions) and fraud figures; never an answer or the key. The figures at the top cover every running "
            + "session of the exam. q: first name, last name, email prefix or a user id (at least 2 characters). "
            + "Cached for 3 seconds.")
    public Mono<ApiResponse<ExamMonitoringResponse>> getExamMonitoring(
            @PathVariable Long examId,
            @RequestParam(required = false) @Parameter(description = "Participant name, email prefix or user id") String q,
            @RequestParam(required = false) @Parameter(description = "LOW, MEDIUM, HIGH or CRITICAL") String riskLevel,
            @RequestParam(required = false) @Parameter(description = "risk (default), violations, startedAt, name or progress") String sort,
            @RequestParam(required = false) @Parameter(description = "asc or desc (default: desc, for name asc)") String order,
            @RequestParam(defaultValue = "0") @Parameter(description = "Page number (0-indexed)") int page,
            @RequestParam(defaultValue = "25") @Parameter(description = "Page size (max 50)") int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        MonitoringService.ListQuery query = new MonitoringService.ListQuery(q, riskLevel, sort, order);
        return Mono.defer(() -> monitoringService.getExamMonitoring(examId, query, page, size, principal.getId(), principal.getRole()))
                .map(ApiResponse::success);
    }

    @GetMapping("/session/{sessionId}/violations")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Get the fraud events of a session", description = "In id order. sinceId: only events with a higher id, "
            + "so a screen that polls asks for the new ones only. limit: default 100, at most 200.")
    public Mono<ApiResponse<List<ViolationResponse>>> getViolations(
            @PathVariable String sessionId,
            @RequestParam(required = false) @Parameter(description = "Only events with an id above this") Long sinceId,
            @RequestParam(defaultValue = "100") @Parameter(description = "Maximum number of events (max 200)") int limit,
            @AuthenticationPrincipal UserPrincipal principal) {

        return monitoringService.getViolations(sessionId, sinceId, limit, principal.getId(), principal.getRole())
                .map(ApiResponse::success);
    }
}
