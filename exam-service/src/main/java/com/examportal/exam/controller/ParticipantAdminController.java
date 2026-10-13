package com.examportal.exam.controller;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.group.ParticipantDtos;
import com.examportal.exam.service.ParticipantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Participant Admin Controller
 * The list of participants for administrators. ADMIN is enforced by path in SecurityConfig
 * (/api/admin/**); @PreAuthorize is a second layer.
 */
@RestController
@RequestMapping("/api/admin/participants")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Participant Administration", description = "Admin-only list of participants")
@SecurityRequirement(name = "bearerAuth")
public class ParticipantAdminController {

    private final ParticipantService participantService;

    @GetMapping
    @Operation(summary = "List participants", description = "Users with the role STUDENT, paged on the server (default 25, max 50). "
            + "Each row carries the first three groups and the number of groups. q: first name, last name, email prefix or a "
            + "user id (at least 2 characters). registeredFrom / registeredTo: days (yyyy-MM-dd) in the exam day zone, inclusive. "
            + "groupId and noGroup cannot be combined.")
    public Mono<ApiResponse<PageResponse<ParticipantDtos.Participant>>> getParticipants(
            @RequestParam(required = false) @Parameter(description = "Name, email prefix or user id") String q,
            @RequestParam(required = false) @Parameter(description = "Members of this group only") Long groupId,
            @RequestParam(required = false) @Parameter(description = "true: only participants in no group") Boolean noGroup,
            @RequestParam(required = false) @Parameter(description = "Email verified or not") Boolean verified,
            @RequestParam(required = false) @Parameter(description = "Locked right now or not") Boolean blocked,
            @RequestParam(required = false) @Parameter(description = "First day, yyyy-MM-dd") String registeredFrom,
            @RequestParam(required = false) @Parameter(description = "Last day, yyyy-MM-dd") String registeredTo,
            @RequestParam(required = false) @Parameter(description = "createdAt (default), name or email") String sort,
            @RequestParam(required = false) @Parameter(description = "asc or desc (default: desc for createdAt, asc otherwise)") String order,
            @RequestParam(defaultValue = "0") @Parameter(description = "Page number (0-indexed)") int page,
            @RequestParam(defaultValue = "25") @Parameter(description = "Page size (max 50)") int size) {

        ParticipantDtos.Filter filter = ParticipantDtos.Filter.builder()
                .q(q).groupId(groupId).noGroup(noGroup).verified(verified).blocked(blocked)
                .registeredFrom(registeredFrom).registeredTo(registeredTo)
                .build();
        return Mono.defer(() -> participantService.getParticipants(filter, sort, order, page, size))
                .map(ApiResponse::success);
    }

    @GetMapping("/summary")
    @Operation(summary = "Participant summary", description = "Registered today, unverified, in no group, and locked right now")
    public Mono<ApiResponse<ParticipantDtos.Summary>> getSummary() {
        return participantService.getSummary()
                .map(ApiResponse::success);
    }
}
