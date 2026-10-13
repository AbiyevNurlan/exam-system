package com.examportal.exam.controller;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.analytics.ExamAnalyticsOverviewResponse;
import com.examportal.common.domain.dto.analytics.QuestionAnalyticsResponse;
import com.examportal.common.security.UserPrincipal;
import com.examportal.common.domain.dto.analytics.PlatformOverviewResponse;
import com.examportal.exam.service.ExamAnalyticsService;
import com.examportal.exam.service.PlatformAnalyticsService;
import org.springframework.web.bind.annotation.RequestParam;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Analytics Controller
 * Figures of one exam for ADMIN and the INSTRUCTOR who owns it, and of the whole platform for
 * ADMIN. Read only; the answers are cached for a minute and carry generatedAt.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Analytics", description = "Exam and platform analytics")
@SecurityRequirement(name = "bearerAuth")
public class AnalyticsController {

    private final ExamAnalyticsService examAnalyticsService;
    private final PlatformAnalyticsService platformAnalyticsService;

    @GetMapping("/api/exams/{examId}/analytics/questions")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Get question analytics", description = "Per question, over each participant's best completed attempt: "
            + "answered, correct, correctRate and averageScoreRate (0..1), difficulty (EASY >= 0.8, MEDIUM >= 0.5, HARD; "
            + "INSUFFICIENT_DATA below 5 participants), discriminationIndex (top 27 % minus bottom 27 %, from 10 participants), "
            + "the options with how often each was selected, grading figures of open-ended questions and the most frequent "
            + "answers of fill-in-the-blank questions. Sessions waiting for a grader are left out. Cached for 60 seconds.")
    public Mono<ApiResponse<QuestionAnalyticsResponse>> getQuestionAnalytics(
            @PathVariable Long examId,
            @AuthenticationPrincipal UserPrincipal principal) {

        return examAnalyticsService.getQuestionAnalytics(examId, principal.getId(), principal.getRole())
                .map(ApiResponse::success);
    }

    @GetMapping("/api/analytics/overview")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get the platform overview", description = "ADMIN only. Exams per status, sessions running now, sessions "
            + "and different participants per day of the last `days` days (1..90, default 30), overall average percentage and "
            + "pass rate, sessions waiting for a grader, the 5 exams with the most sessions, and aggregate user counts from "
            + "auth-service (users is null and usersAvailable false when it cannot be reached). Cached for 60 seconds.")
    public Mono<ApiResponse<PlatformOverviewResponse>> getPlatformOverview(
            @RequestParam(required = false) @Parameter(description = "Days to look back (1..90, default 30)") Integer days) {

        return Mono.defer(() -> platformAnalyticsService.getOverview(days))
                .map(ApiResponse::success);
    }

    @GetMapping("/api/exams/{examId}/analytics/overview")
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Get the analytics overview of an exam", description = "Score distribution in ten buckets, participant "
            + "and session counts, sessions closed by the clock, average duration, average and median percentage, pass rate "
            + "(0..100), attempts per participant and sessions per day of the last 60 days. Cached for 60 seconds.")
    public Mono<ApiResponse<ExamAnalyticsOverviewResponse>> getExamOverview(
            @PathVariable Long examId,
            @AuthenticationPrincipal UserPrincipal principal) {

        return examAnalyticsService.getOverview(examId, principal.getId(), principal.getRole())
                .map(ApiResponse::success);
    }
}
