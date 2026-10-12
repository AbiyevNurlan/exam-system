package com.examportal.auth.controller;

import com.examportal.auth.service.AdminUserService;
import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.auth.AdminUserResponse;
import com.examportal.common.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * Admin User Controller
 * User search and account unlock for administrators.
 * ADMIN is enforced by path in SecurityConfig (/auth/admin/**); @PreAuthorize is a second layer.
 */
@Slf4j
@RestController
@RequestMapping("/auth/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "User Administration", description = "Admin-only user search and account unlock")
@SecurityRequirement(name = "bearerAuth")
public class AdminUserController {

    private final AdminUserService adminUserService;

    /**
     * Search users
     */
    @GetMapping("/users")
    @Operation(summary = "Search users", description = "Filter by email, id, name prefix or lock state; paginated, max 50 per page")
    public Mono<ResponseEntity<ApiResponse<PageResponse<AdminUserResponse>>>> searchUsers(
            @RequestParam(name = "email", required = false) String email,
            @RequestParam(name = "id", required = false) String id,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "locked", required = false) String locked,
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size,
            @AuthenticationPrincipal UserPrincipal principal) {

        log.info("Admin user search by admin: {}", principal.getId());

        // Mono.defer turns a validation error thrown while building the query into a normal error signal
        return Mono.defer(() -> adminUserService.searchUsers(email, id, name, locked, page, size))
                .map(users -> ResponseEntity.ok(ApiResponse.success(users)));
    }

    /**
     * Unlock a user account
     */
    @PostMapping("/users/{id}/unlock")
    @Operation(summary = "Unlock user", description = "Clear the lock and the failed-login counter of a user")
    public Mono<ResponseEntity<ApiResponse<Void>>> unlockUser(
            @PathVariable("id") String id,
            @AuthenticationPrincipal UserPrincipal principal) {

        log.info("Unlock request for user: {} by admin: {}", id, principal.getId());

        return Mono.defer(() -> adminUserService.unlockUser(id, principal.getEmail()))
                .then(Mono.just(ResponseEntity.ok(
                        ApiResponse.<Void>success(null, "User unlocked successfully"))));
    }
}
