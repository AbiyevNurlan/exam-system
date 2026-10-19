package com.examportal.exam.controller;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.instructor.InstructorDtos;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.service.InstructorLimitsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Instructor Limits Controller (B13)
 * The limits of one teacher, for administrators only (path rule /api/admin/** and @PreAuthorize).
 */
@RestController
@RequestMapping("/api/admin/instructor-limits")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Instructor Administration", description = "Teachers' limits (ADMIN only)")
@SecurityRequirement(name = "bearerAuth")
public class InstructorLimitsController {

    private final InstructorLimitsService limitsService;

    @GetMapping("/{instructorId}")
    @Operation(summary = "Limits of a teacher", description = "maxGroups, maxMembers (distinct people in the own groups, "
            + "pending invites included), maxExams, maxMediaBytes as they apply now; defaults lists those that are the "
            + "system default; used* what the teacher has now.")
    public Mono<ApiResponse<InstructorDtos.Limits>> get(@PathVariable UUID instructorId) {
        return limitsService.get(instructorId).map(ApiResponse::success);
    }

    @PutMapping("/{instructorId}")
    @Operation(summary = "Set the limits of a teacher", description = "Each of maxGroups, maxMembers, maxExams and "
            + "maxMediaBytes: a positive number, or null for the system default. Lowering a limit removes nothing; a "
            + "teacher above it can add no more (409 TEACHER_LIMIT_REACHED). Audited as INSTRUCTOR_LIMITS_CHANGED.")
    public Mono<ApiResponse<InstructorDtos.Limits>> set(@PathVariable UUID instructorId,
                                                        @Valid @RequestBody InstructorDtos.Limits request,
                                                        @AuthenticationPrincipal UserPrincipal principal) {
        return limitsService.set(instructorId, request, principal.getId()).map(ApiResponse::success);
    }
}
