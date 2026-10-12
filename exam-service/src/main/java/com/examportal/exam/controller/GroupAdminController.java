package com.examportal.exam.controller;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.group.AddGroupMemberRequest;
import com.examportal.common.domain.dto.group.AddGroupMemberResponse;
import com.examportal.common.domain.dto.group.CsvImportResponse;
import com.examportal.common.domain.dto.group.GroupInviteResponse;
import com.examportal.common.domain.dto.group.GroupMemberResponse;
import com.examportal.common.domain.dto.group.GroupRequest;
import com.examportal.common.domain.dto.group.GroupResponse;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.service.GroupImportService;
import com.examportal.exam.service.GroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Group Admin Controller
 * User groups and their members. ADMIN is enforced by path in SecurityConfig (/api/admin/**);
 * @PreAuthorize is a second layer.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/groups")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Group Administration", description = "Admin-only user groups and membership")
@SecurityRequirement(name = "bearerAuth")
public class GroupAdminController {

    private final GroupService groupService;
    private final GroupImportService importService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a group")
    public Mono<ApiResponse<GroupResponse>> createGroup(
            @Valid @RequestBody GroupRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        return groupService.createGroup(request, principal.getId())
                .map(group -> ApiResponse.success(group, "Group created successfully"));
    }

    @GetMapping
    @Operation(summary = "Search groups", description = "Optional name filter; sorted by name, max 50 per page")
    public Mono<ApiResponse<PageResponse<GroupResponse>>> searchGroups(
            @RequestParam(required = false) @Parameter(description = "Part of the name, case-insensitive") String name,
            @RequestParam(defaultValue = "0") @Parameter(description = "Page number (0-indexed)") int page,
            @RequestParam(defaultValue = "25") @Parameter(description = "Page size (max 50)") int size) {

        return groupService.searchGroups(name, page, size)
                .map(ApiResponse::success);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a group", description = "Group with its member and pending invite counts")
    public Mono<ApiResponse<GroupResponse>> getGroup(@PathVariable Long id) {
        return groupService.getGroup(id)
                .map(ApiResponse::success);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a group")
    public Mono<ApiResponse<GroupResponse>> updateGroup(
            @PathVariable Long id,
            @Valid @RequestBody GroupRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        return groupService.updateGroup(id, request, principal.getId())
                .map(group -> ApiResponse.success(group, "Group updated successfully"));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a group", description = "Soft delete; members and audit trail are kept")
    public Mono<Void> deleteGroup(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {

        return groupService.deleteGroup(id, principal.getId());
    }

    @GetMapping("/{id}/members")
    @Operation(summary = "List members", description = "Paginated members of a group, max 50 per page")
    public Mono<ApiResponse<PageResponse<GroupMemberResponse>>> getMembers(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") @Parameter(description = "Page number (0-indexed)") int page,
            @RequestParam(defaultValue = "25") @Parameter(description = "Page size (max 50)") int size) {

        return groupService.getMembers(id, page, size)
                .map(ApiResponse::success);
    }

    @PostMapping("/{id}/members")
    @Operation(summary = "Add a member", description = "By userId or by email; an email without a verified account becomes a pending invite")
    public Mono<ApiResponse<AddGroupMemberResponse>> addMember(
            @PathVariable Long id,
            @Valid @RequestBody AddGroupMemberRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        return groupService.addMember(id, request, principal.getId())
                .map(ApiResponse::success);
    }

    @PostMapping(value = "/{id}/members/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import members from CSV", description = "Multipart part 'file': one email per line, optional header, max 20000 rows and 2 MB. dryRun=true only reports what would happen")
    public Mono<ApiResponse<CsvImportResponse>> importMembers(
            @PathVariable Long id,
            @RequestPart("file") FilePart file,
            @RequestParam(defaultValue = "false") @Parameter(description = "Report only, store nothing") boolean dryRun,
            @AuthenticationPrincipal UserPrincipal principal) {

        return importService.importMembers(id, file, dryRun, principal.getId())
                .map(ApiResponse::success);
    }

    @GetMapping("/{id}/invites")
    @Operation(summary = "List pending invites", description = "Paginated pending invites of a group with masked emails, max 50 per page")
    public Mono<ApiResponse<PageResponse<GroupInviteResponse>>> getInvites(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") @Parameter(description = "Page number (0-indexed)") int page,
            @RequestParam(defaultValue = "25") @Parameter(description = "Page size (max 50)") int size) {

        return groupService.getInvites(id, page, size)
                .map(ApiResponse::success);
    }

    @DeleteMapping("/{id}/invites/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cancel a pending invite")
    public Mono<Void> cancelInvite(
            @PathVariable Long id,
            @PathVariable Long inviteId,
            @AuthenticationPrincipal UserPrincipal principal) {

        return groupService.cancelInvite(id, inviteId, principal.getId());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a member", description = "Blocks new exam starts; a session already in progress can be finished")
    public Mono<Void> removeMember(
            @PathVariable Long id,
            @PathVariable UUID userId,
            @AuthenticationPrincipal UserPrincipal principal) {

        return groupService.removeMember(id, userId, principal.getId());
    }
}