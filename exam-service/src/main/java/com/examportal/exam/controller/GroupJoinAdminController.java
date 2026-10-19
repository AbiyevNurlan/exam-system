package com.examportal.exam.controller;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.group.JoinDtos;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.service.GroupJoinService;
import com.examportal.exam.service.InstructorLimitsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Group Join Admin Controller
 * The join link of a group and the requests made through it. ADMIN for every group, INSTRUCTOR for
 * the own ones (GroupAccessPolicy; another owner's group is a 404). Roles by path in SecurityConfig
 * (/api/groups/{id}/**) and by @PreAuthorize.
 */
@RestController
@RequestMapping("/api/groups/{id}")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
@Tag(name = "Group Administration", description = "User groups and membership: ADMIN all, INSTRUCTOR the own ones")
@SecurityRequirement(name = "bearerAuth")
public class GroupJoinAdminController {

    private final GroupJoinService joinService;
    private final InstructorLimitsService limitsService;

    @GetMapping("/join-link")
    @Operation(summary = "Get the join link", description = "Code, settings, uses and open requests; 404 JOIN_LINK_NOT_FOUND when the group has none")
    public Mono<ApiResponse<JoinDtos.LinkResponse>> getLink(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return joinService.getLink(id, principal)
                .map(ApiResponse::success);
    }

    @PostMapping("/join-link")
    @Operation(summary = "Create the join link (again)", description = "A new code; a code the group had before stops working "
            + "and the uses start from zero. Optional body: requiresApproval, expiresAt, maxUses (omitted = as before).")
    public Mono<ApiResponse<JoinDtos.LinkResponse>> createLink(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) JoinDtos.LinkRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        return Mono.defer(() -> joinService.createLink(id, request, principal))
                .map(link -> ApiResponse.success(link, "Join link created successfully"));
    }

    @PatchMapping("/join-link")
    @Operation(summary = "Change the join link", description = "enabled, requiresApproval, expiresAt, maxUses; the code stays. "
            + "A field that is omitted keeps its value; expiresAt and maxUses are removed with null.")
    public Mono<ApiResponse<JoinDtos.LinkResponse>> updateLink(
            @PathVariable Long id,
            @Valid @RequestBody JoinDtos.LinkRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        return Mono.defer(() -> joinService.updateLink(id, request, principal))
                .map(link -> ApiResponse.success(link, "Join link updated successfully"));
    }

    @GetMapping("/join-requests")
    @Operation(summary = "List join requests", description = "Requests of the group in one status (PENDING by default), oldest first, max 50 per page")
    public Mono<ApiResponse<PageResponse<JoinDtos.Request>>> getRequests(
            @PathVariable Long id,
            @RequestParam(required = false) @Parameter(description = "PENDING (default), APPROVED or REJECTED") String status,
            @RequestParam(defaultValue = "0") @Parameter(description = "Page number (0-indexed)") int page,
            @RequestParam(defaultValue = "25") @Parameter(description = "Page size (max 50)") int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        return Mono.defer(() -> joinService.getRequests(id, status, page, size, principal))
                .map(ApiResponse::success);
    }

    @PostMapping("/join-requests/approve")
    @Operation(summary = "Approve join requests", description = "Up to 500 request ids; requests that are not open are skipped")
    public Mono<ApiResponse<JoinDtos.DecisionResult>> approve(
            @PathVariable Long id,
            @Valid @RequestBody JoinDtos.Decision request,
            @AuthenticationPrincipal UserPrincipal principal) {

        // B13: approved people become members of the teacher's group (at most one new member per request)
        int adding = request.getRequestIds() == null ? 0 : request.getRequestIds().size();
        return limitsService.requireRoom(principal.getId(), principal.getRole(), InstructorLimitsService.MEMBERS, adding)
                .then(Mono.defer(() -> joinService.decide(id, request.getRequestIds(), true, principal)))
                .map(ApiResponse::success);
    }

    @PostMapping("/join-requests/reject")
    @Operation(summary = "Reject join requests", description = "Up to 500 request ids; requests that are not open are skipped")
    public Mono<ApiResponse<JoinDtos.DecisionResult>> reject(
            @PathVariable Long id,
            @Valid @RequestBody JoinDtos.Decision request,
            @AuthenticationPrincipal UserPrincipal principal) {

        return joinService.decide(id, request.getRequestIds(), false, principal)
                .map(ApiResponse::success);
    }

    @PostMapping("/join-requests/{requestId}/approve")
    @Operation(summary = "Approve one join request", description = "404 JOIN_REQUEST_NOT_FOUND when it is not an open request of this group")
    public Mono<ApiResponse<JoinDtos.DecisionResult>> approveOne(
            @PathVariable Long id,
            @PathVariable Long requestId,
            @AuthenticationPrincipal UserPrincipal principal) {

        return limitsService.requireRoom(principal.getId(), principal.getRole(), InstructorLimitsService.MEMBERS, 1)
                .then(Mono.defer(() -> joinService.decideOne(id, requestId, true, principal)))
                .map(ApiResponse::success);
    }

    @PostMapping("/join-requests/{requestId}/reject")
    @Operation(summary = "Reject one join request", description = "404 JOIN_REQUEST_NOT_FOUND when it is not an open request of this group")
    public Mono<ApiResponse<JoinDtos.DecisionResult>> rejectOne(
            @PathVariable Long id,
            @PathVariable Long requestId,
            @AuthenticationPrincipal UserPrincipal principal) {

        return joinService.decideOne(id, requestId, false, principal)
                .map(ApiResponse::success);
    }
}
