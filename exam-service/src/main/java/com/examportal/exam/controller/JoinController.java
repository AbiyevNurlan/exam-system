package com.examportal.exam.controller;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.group.JoinDtos;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.service.GroupJoinService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.examportal.common.domain.dto.PageResponse;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Mono;

/**
 * Join Controller
 * Joining a group with its link. Asking what a code is for needs no token (the link is opened
 * before the visitor has an account); joining needs one.
 */
@RestController
@RequestMapping("/api/join")
@RequiredArgsConstructor
@Tag(name = "Join", description = "Joining a group with a link")
public class JoinController {

    private final GroupJoinService joinService;

    @GetMapping("/{code}")
    @Operation(summary = "What a join code is for", description = "The name of the group and whether joining needs approval; "
            + "no token needed. An unknown, switched off, expired or used up code: 404 JOIN_CODE_INVALID, always the same answer.")
    public Mono<ApiResponse<JoinDtos.Info>> getInfo(@PathVariable String code) {
        return joinService.getInfo(code)
                .map(ApiResponse::success);
    }

    @GetMapping("/requests/mine")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "My join requests", description = "The caller's own requests, newest first, max 50 per page: "
            + "groupName, status (PENDING, APPROVED, REJECTED), createdAt, decidedAt. The newest rejected request of a "
            + "group carries canRequestAgainAt while the rejection cooldown runs (a new request answers 409 "
            + "JOIN_REQUEST_COOLDOWN until then).")
    public Mono<ApiResponse<PageResponse<JoinDtos.OwnRequest>>> myRequests(
            @RequestParam(defaultValue = "0") @Parameter(description = "Page number (0-indexed)") int page,
            @RequestParam(defaultValue = "20") @Parameter(description = "Page size (max 50)") int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        return joinService.myRequests(principal.getId(), page, size)
                .map(ApiResponse::success);
    }

    @PostMapping("/{code}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Join the group of a code", description = "For participants (STUDENT) with a verified email: "
            + "status MEMBER, or PENDING when the link needs approval. Idempotent. 404 JOIN_CODE_INVALID, "
            + "403 EMAIL_NOT_VERIFIED, 403 JOIN_PARTICIPANTS_ONLY, 409 JOIN_REQUEST_COOLDOWN (the last request to this group "
            + "was rejected less than the cooldown ago; error.details.canRequestAgainAt).")
    public Mono<ApiResponse<JoinDtos.JoinResult>> join(
            @PathVariable String code,
            @AuthenticationPrincipal UserPrincipal principal) {

        return joinService.join(code, principal.getId(), principal.getRole())
                .map(ApiResponse::success);
    }
}
