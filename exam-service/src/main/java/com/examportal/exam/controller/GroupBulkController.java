package com.examportal.exam.controller;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.group.ParticipantDtos;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.service.GroupBulkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Group Bulk Controller
 * Adding many participants to a group at once. By ids: ADMIN for every group, INSTRUCTOR for the own
 * ones (GroupAccessPolicy). By filter: ADMIN only, because a filter reaches every participant of the
 * platform. Roles by path in SecurityConfig (/api/groups/{id}/**) and by @PreAuthorize.
 */
@Slf4j
@RestController
@RequestMapping("/api/groups/{id}/members")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
@Tag(name = "Group Administration", description = "User groups and membership: ADMIN all, INSTRUCTOR the own ones")
@SecurityRequirement(name = "bearerAuth")
public class GroupBulkController {

    private final GroupBulkService bulkService;

    @PostMapping("/bulk-by-filter")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Add everybody matching a filter", description = "The filter is the one of the participant list. "
            + "dryRun=true writes nothing and answers {matched, alreadyMembers, willAdd, cap}. A real run needs expectedCount "
            + "(willAdd of the dry run): 409 FILTER_COUNT_CHANGED when it no longer holds, 422 TOO_MANY_MATCHES above 20 000; "
            + "nothing is written in either case. Idempotent; one audit row per real run.")
    public Mono<ApiResponse<ParticipantDtos.BulkResult>> addByFilter(
            @PathVariable Long id,
            @Valid @RequestBody ParticipantDtos.BulkByFilterRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        log.info("Bulk addition by filter to group: {} by user: {} (dryRun: {})", id, principal.getId(), request.isDryRun());

        return Mono.defer(() -> bulkService.addByFilter(id, request, principal))
                .map(ApiResponse::success);
    }

    @PostMapping("/bulk-by-ids")
    @Operation(summary = "Add a list of participants", description = "Up to 1000 user ids (a page selection). Ids that are "
            + "unknown or not participants are left out. Same answer as bulk-by-filter; idempotent; one audit row per real run.")
    public Mono<ApiResponse<ParticipantDtos.BulkResult>> addByIds(
            @PathVariable Long id,
            @Valid @RequestBody ParticipantDtos.BulkByIdsRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        log.info("Bulk addition by ids to group: {} by user: {} (dryRun: {})", id, principal.getId(), request.isDryRun());

        return Mono.defer(() -> bulkService.addByIds(id, request, principal))
                .map(ApiResponse::success);
    }
}
