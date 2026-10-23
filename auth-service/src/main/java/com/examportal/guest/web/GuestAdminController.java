package com.examportal.guest.web;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.security.UserPrincipal;
import com.examportal.guest.GuestAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * B13a item 5: the admin's guests. ADMIN only, by path in GuestSecurityConfig and here again by the method.
 */
@RestController
@RequestMapping("/api/admin/guests")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Guests (admin)", description = "Guest participants of paid open exams")
public class GuestAdminController {

    private final GuestAdminService admin;

    @GetMapping
    @Operation(summary = "Guests, searched and sorted", description = "q: a 7-digit code, or the start of the email or a name; "
            + "sortBy: code, firstName, lastName, email, createdAt, lastLoginAt; dir: asc or desc")
    public Mono<ResponseEntity<ApiResponse<GuestDtos.AdminGuestPage>>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String dir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return admin.page(q, sortBy, dir, page, size).map(found -> ResponseEntity.ok(ApiResponse.success(found)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "The card of a guest")
    public Mono<ResponseEntity<ApiResponse<GuestDtos.AdminGuestCard>>> card(@PathVariable UUID id) {
        return admin.card(id).map(guest -> ResponseEntity.ok(ApiResponse.success(GuestDtos.AdminGuestCard.of(guest))));
    }

    @PatchMapping("/{id}/block")
    @Operation(summary = "Block or unblock a guest", description = "The reason is required; a block ends the guest's sign-ins")
    public Mono<ResponseEntity<ApiResponse<GuestDtos.AdminGuestCard>>> block(@AuthenticationPrincipal UserPrincipal principal,
                                                                             @PathVariable UUID id,
                                                                             @Valid @RequestBody GuestDtos.BlockRequest request) {
        return admin.block(principal.getId(), id, request)
                .map(guest -> ResponseEntity.ok(ApiResponse.success(GuestDtos.AdminGuestCard.of(guest))));
    }
}
