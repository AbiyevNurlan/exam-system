package com.examportal.exam.controller;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.AuditEntryResponse;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.exam.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.examportal.common.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Audit Controller
 * The audit list for administrators. ADMIN is enforced by path in SecurityConfig (/api/admin/**);
 * @PreAuthorize is a second layer. B13: GET is open to INSTRUCTOR too (path rule and method annotation), limited to
 * the teacher's own actions in exam-service (AuditService).
 */
@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Audit", description = "Admin-only audit list")
@SecurityRequirement(name = "bearerAuth")
public class AuditController {

    private final AuditService auditService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
    @Operation(summary = "Audit list", description = "Entries of exam-service (groups, memberships, join links, exams, "
            + "grading, result exports) and auth-service (accounts), newest first, max 50 per page and 10 000 rows deep. "
            + "action: exact name; actorId: user id; from (inclusive) / to (exclusive): ISO date-times, UTC when no offset; "
            + "q: part of the action or the details (2 to 100 characters); source: EXAM_SERVICE or AUTH_SERVICE. "
            + "INSTRUCTOR (B13): only the own actions in exam-service, emails in the details masked; another actorId or "
            + "source AUTH_SERVICE gives an empty page.")
    public Mono<ApiResponse<PageResponse<AuditEntryResponse>>> list(
            @RequestParam(required = false) @Parameter(description = "Action, e.g. MEMBER_ADDED") String action,
            @RequestParam(required = false) @Parameter(description = "User id of the actor") String actorId,
            @RequestParam(required = false) @Parameter(description = "From, ISO date-time (UTC)") String from,
            @RequestParam(required = false) @Parameter(description = "To, ISO date-time (UTC), exclusive") String to,
            @RequestParam(required = false) @Parameter(description = "Text in the action or the details") String q,
            @RequestParam(required = false) @Parameter(description = "EXAM_SERVICE or AUTH_SERVICE") String source,
            @RequestParam(defaultValue = "0") @Parameter(description = "Page number (0-indexed)") int page,
            @RequestParam(defaultValue = "25") @Parameter(description = "Page size (max 50)") int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        return Mono.defer(() -> auditService.list(action, actorId, from, to, q, source, page, size,
                        principal.getId(), principal.getRole()))
                .map(ApiResponse::success);
    }
}
