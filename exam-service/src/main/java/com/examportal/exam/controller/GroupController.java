package com.examportal.exam.controller;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.group.GroupSummaryResponse;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.service.GroupService;
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
 * Group Controller
 * Read-only list of group names for choosing the audience of an exam: every group for ADMIN, the own
 * ones for INSTRUCTOR. Managing groups is under /api/admin/groups (GroupAccessPolicy).
 */
@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
@Tag(name = "Groups", description = "Group names for choosing an exam audience")
@SecurityRequirement(name = "bearerAuth")
public class GroupController {

    private final GroupService groupService;

    @GetMapping
    @Operation(summary = "List groups", description = "Id and name only; optional name filter, sorted by name, max 50 per page")
    public Mono<ApiResponse<PageResponse<GroupSummaryResponse>>> listGroups(
            @RequestParam(required = false) @Parameter(description = "Part of the name, case-insensitive") String name,
            @RequestParam(defaultValue = "0") @Parameter(description = "Page number (0-indexed)") int page,
            @RequestParam(defaultValue = "25") @Parameter(description = "Page size (max 50)") int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        return groupService.listGroupNames(name, page, size, principal)
                .map(ApiResponse::success);
    }
}
