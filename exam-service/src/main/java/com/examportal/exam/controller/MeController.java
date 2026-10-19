package com.examportal.exam.controller;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.MyStatsResponse;
import com.examportal.common.domain.dto.profile.ParticipantProfileDtos;
import com.examportal.exam.client.UserDirectoryClient;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final UserDirectoryClient userDirectory;

    @GetMapping("/stats")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "My exam statistics", description = "examsTaken (different exams with a submitted attempt), "
            + "examsGraded (different exams with a final, visible result), averagePercent and bestPercent over the best "
            + "visible attempt per exam (null when there is none), lastResult (examId, examTitle, percentage, passed, "
            + "submittedAt), pendingGradingCount (submitted attempts waiting for a grader). A result that is withheld "
            + "(open answers not graded yet, or not shown before the exam ends) counts only in examsTaken. B13: hasGroups, hasExams, "
            + "hasMembers: whether a teacher has an own group, exam, member (ADMIN: anybody; always false for a participant).")
    public Mono<ApiResponse<MyStatsResponse>> myStats(@AuthenticationPrincipal UserPrincipal principal) {
        Mono<MyStatsResponse> stats = statsRepository.forUser(principal.getId(), LocalDateTime.now());
        String role = principal.getRole();
        if (!"ADMIN".equals(role) && !"INSTRUCTOR".equals(role)) {
            return stats.map(ApiResponse::success);
        }
        // B13: what the teacher has already (ADMIN: anybody), for the first steps of a new teacher
        return stats.zipWith(statsRepository.teaching("ADMIN".equals(role) ? null : principal.getId()), (figures, teaching) -> {
                    figures.setHasGroups(teaching.hasGroups());
                    figures.setHasExams(teaching.hasExams());
                    figures.setHasMembers(teaching.hasMembers());
                    return figures;
                })
                .map(ApiResponse::success);
    }

    @GetMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "My participant profile", description = "profile (null when none was saved yet: every account from "
            + "before B11), categories (the ones that may be chosen) and requiredFields (per category the fields that must be "
            + "filled; only the category when a category has no entry). Names and phone stay with GET /auth/me.")
    public Mono<ApiResponse<ParticipantProfileDtos.MyProfile>> myProfile(@AuthenticationPrincipal UserPrincipal principal) {
        return userDirectory.profile(principal.getId()).map(ApiResponse::success);
    }

    @PutMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Save my participant profile", description = "The whole profile: category (required, one of "
            + "categories) and institution (200), facultyOrDepartment (200), courseOrGrade (50), workplace (200), position (100), "
            + "city (100), birthDate (yyyy-MM-dd, 1900-01-01..today). Text is trimmed and cleaned of HTML; a field left out or "
            + "empty is stored as empty. 400 VALIDATION_ERROR names the field. Only the own profile: there is no user parameter.")
    public Mono<ApiResponse<ParticipantProfileDtos.Profile>> saveMyProfile(@AuthenticationPrincipal UserPrincipal principal,
                                                                         @RequestBody ParticipantProfileDtos.UpdateRequest request) {
        return userDirectory.saveProfile(principal.getId(), principal.getId(), request).map(ApiResponse::success);
    }
}
