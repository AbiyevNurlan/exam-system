package com.examportal.guest.web;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.guest.GuestFeature;
import com.examportal.guest.GuestService;
import com.examportal.guest.security.GuestPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * B13a item 3: the guest's own data (only with a guest token, GuestSecurityConfig) and the feature switch (open,
 * 200 whether PAID_ACCESS_ENABLED is on or off).
 */
@RestController
@RequestMapping("/api/guest")
@RequiredArgsConstructor
@Tag(name = "Guest", description = "A signed-in guest participant of paid open exams")
public class GuestController {

    private final GuestService guests;
    private final GuestFeature feature;

    @GetMapping("/feature")
    @Operation(summary = "Whether the guest sign-in of paid open exams is on",
            description = "paymentsDemo: payments are a DEMO (no real money is charged)")
    public Mono<ResponseEntity<ApiResponse<GuestDtos.FeatureResponse>>> feature() {
        return Mono.just(ResponseEntity.ok(ApiResponse.success(new GuestDtos.FeatureResponse(feature.isEnabled(), feature.paymentsDemo()))));
    }

    @GetMapping("/me")
    @Operation(summary = "The signed-in guest")
    public Mono<ResponseEntity<ApiResponse<GuestDtos.GuestView>>> me(@AuthenticationPrincipal GuestPrincipal principal) {
        return guests.current(principal).map(guest -> ResponseEntity.ok(ApiResponse.success(GuestDtos.GuestView.of(guest))));
    }

    @PatchMapping("/me")
    @Operation(summary = "Change the name, surname or phone", description = "The email does not change")
    public Mono<ResponseEntity<ApiResponse<GuestDtos.GuestView>>> update(@AuthenticationPrincipal GuestPrincipal principal,
                                                                         @Valid @RequestBody GuestDtos.UpdateMeRequest request) {
        return guests.update(principal, request).map(guest -> ResponseEntity.ok(ApiResponse.success(GuestDtos.GuestView.of(guest))));
    }
}
