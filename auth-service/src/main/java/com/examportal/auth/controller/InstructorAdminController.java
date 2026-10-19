package com.examportal.auth.controller;

import com.examportal.auth.service.InstructorAdminService;
import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.instructor.InstructorDtos;
import com.examportal.common.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Instructor Administration Controller (B13)
 * Teachers are made and managed by administrators only (path rule /api/admin/** in SecurityConfig and
 * @PreAuthorize); the open registration always makes a STUDENT. The gateway routes /api/admin/instructors/** here.
 */
@RestController
@RequestMapping("/api/admin/instructors")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Instructor Administration", description = "Teachers made and managed by administrators")
@SecurityRequirement(name = "bearerAuth")
public class InstructorAdminController {

    private final InstructorAdminService instructorService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a teacher", description = "An INSTRUCTOR account, active, email marked verified (no code "
            + "is sent). temporaryPassword (16 characters) is in this answer only and nowhere else; it works for 7 days and "
            + "must be changed first (mustChangePassword: until then the tokens reach only POST /auth/change-password and "
            + "POST /auth/logout). 409 EMAIL_ALREADY_EXISTS for an email of any existing account.")
    public Mono<ApiResponse<InstructorDtos.Created>> create(@Valid @RequestBody InstructorDtos.CreateRequest request,
                                                            @AuthenticationPrincipal UserPrincipal principal) {
        return instructorService.create(request, principal.getId()).map(created -> ApiResponse.success(created, "Teacher created"));
    }

    @GetMapping
    @Operation(summary = "List teachers", description = "Newest first, paged (default 25, max 100). active: true or false; "
            + "q: start of a name or email, or a 7-digit participant code. Each row: name, code, email, status (ACTIVE, "
            + "PASSWORD_CHANGE_REQUIRED, DEACTIVATED), lastLoginAt, and from exam-service groupCount, memberCount, examCount, "
            + "activeExamCount (null with figuresAvailable false when exam-service cannot be asked).")
    public Mono<ApiResponse<PageResponse<InstructorDtos.Row>>> list(
            @RequestParam(required = false) @Parameter(description = "true: active, false: deactivated") Boolean active,
            @RequestParam(required = false) @Parameter(description = "Name, email or 7-digit code") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return Mono.defer(() -> instructorService.list(active, q, page, size)).map(ApiResponse::success);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Change a teacher", description = "firstName, lastName, active (a field left out keeps its value). "
            + "Deactivating ends every session at once (refresh and access tokens) and keeps everything the teacher made; "
            + "with exams that are ACTIVE or SCHEDULED it needs confirm=true (409 INSTRUCTOR_HAS_ACTIVE_EXAMS otherwise). "
            + "503 TOKEN_REVOCATION_UNAVAILABLE when the sessions cannot be ended (nothing changed). 404 INSTRUCTOR_NOT_FOUND "
            + "for an id that is not a teacher.")
    public Mono<ApiResponse<InstructorDtos.Row>> update(@PathVariable UUID id,
                                                        @Valid @RequestBody InstructorDtos.UpdateRequest request,
                                                        @RequestParam(defaultValue = "false") boolean confirm,
                                                        @AuthenticationPrincipal UserPrincipal principal) {
        return Mono.defer(() -> instructorService.update(id, request, confirm, principal.getId())).map(ApiResponse::success);
    }

    @PostMapping("/{id}/reset-password")
    @Operation(summary = "Give a teacher a new temporary password", description = "temporaryPassword is in this answer only; "
            + "the teacher must change it first, it works for 7 days, every session of the teacher ends at once. "
            + "Rate limited by the gateway.")
    public Mono<ApiResponse<InstructorDtos.Created>> resetPassword(@PathVariable UUID id,
                                                                   @AuthenticationPrincipal UserPrincipal principal) {
        return instructorService.resetPassword(id, principal.getId()).map(ApiResponse::success);
    }
}
