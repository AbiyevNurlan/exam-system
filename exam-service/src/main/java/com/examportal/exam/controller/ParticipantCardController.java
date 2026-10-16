package com.examportal.exam.controller;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.participant.ParticipantCardDtos;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.service.ParticipantCardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Participant Card Controller (B11)
 * The participant card for administrators and instructors. ADMIN sees every account; an INSTRUCTOR only a
 * member of an own group (404 otherwise) and of that member only the own groups and exams. The path rule in
 * SecurityConfig lets INSTRUCTOR reach only these two paths under /api/admin/participants; the list, the
 * summary and every write stay ADMIN only.
 */
@RestController
@RequestMapping("/api/admin/participants")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'INSTRUCTOR')")
@Tag(name = "Participant Card", description = "One participant on one screen")
@SecurityRequirement(name = "bearerAuth")
public class ParticipantCardController {

    private final ParticipantCardService cardService;

    @GetMapping("/{userId:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}")
    @Operation(summary = "Participant card", description = "participantCode, names, email, phone, role, registeredAt, "
            + "emailVerified, active, blocked, lockedUntil, profile (null when none), scope (ALL / OWN), groups, the first "
            + "20 attempts (sessions, a page), stats (the rule of GET /api/me/stats), recentEvents (the newest 20 of both "
            + "audit logs with actor and target codes; ADMIN only). INSTRUCTOR: only a member of an own group, 404 otherwise.")
    public Mono<ApiResponse<ParticipantCardDtos.Card>> card(@PathVariable UUID userId,
                                                            @AuthenticationPrincipal UserPrincipal principal) {
        return cardService.card(userId, principal.getId(), principal.getRole()).map(ApiResponse::success);
    }

    @GetMapping("/{userId:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}/sessions")
    @Operation(summary = "Attempts of a participant", description = "Newest first, paged (default 20, max 50); the same "
            + "rule as the card: an INSTRUCTOR sees only the attempts at own exams.")
    public Mono<ApiResponse<PageResponse<ParticipantCardDtos.SessionRow>>> sessions(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") @Parameter(description = "Page number (0-indexed)") int page,
            @RequestParam(defaultValue = "20") @Parameter(description = "Page size (max 50)") int size,
            @AuthenticationPrincipal UserPrincipal principal) {
        return cardService.sessionsPage(userId, principal.getId(), principal.getRole(), page, size).map(ApiResponse::success);
    }
}
