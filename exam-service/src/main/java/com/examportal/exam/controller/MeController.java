package com.examportal.exam.controller;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.MyStatsResponse;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.repository.MyStatsRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * The signed-in user's own figures. There is no user id parameter: the user is always the one of the token.
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
@Tag(name = "Me", description = "The signed-in user's own figures")
@SecurityRequirement(name = "bearerAuth")
public class MeController {

    private final MyStatsRepository statsRepository;

    @GetMapping("/stats")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "My exam statistics", description = "examsTaken (different exams with a submitted attempt), "
            + "examsGraded (different exams with a final, visible result), averagePercent and bestPercent over the best "
            + "visible attempt per exam (null when there is none), lastResult (examId, examTitle, percentage, passed, "
            + "submittedAt), pendingGradingCount (submitted attempts waiting for a grader). A result that is withheld "
            + "(open answers not graded yet, or not shown before the exam ends) counts only in examsTaken.")
    public Mono<ApiResponse<MyStatsResponse>> myStats(@AuthenticationPrincipal UserPrincipal principal) {
        return statsRepository.forUser(principal.getId(), LocalDateTime.now())
                .map(ApiResponse::success);
    }
}
