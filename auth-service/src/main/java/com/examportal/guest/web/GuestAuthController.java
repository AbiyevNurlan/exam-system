package com.examportal.guest.web;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.guest.GuestAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * B13a item 3: the guest sign-in steps, open to everybody (GuestSecurityConfig); 404 FEATURE_DISABLED while
 * PAID_ACCESS_ENABLED is false. The gateway gives these paths their own rate-limit buckets.
 */
@RestController
@RequestMapping("/auth/guest")
@RequiredArgsConstructor
@Tag(name = "Guest sign-in", description = "Email and a one-time code, no password (paid open exams)")
public class GuestAuthController {

    private final GuestAuthService guestAuth;

    @PostMapping("/otp/request")
    @Operation(summary = "Send a sign-in code", description = "Always answers the same, whether or not a code was sent")
    public Mono<ResponseEntity<ApiResponse<Void>>> requestCode(@Valid @RequestBody GuestDtos.OtpRequest request,
                                                                ServerWebExchange exchange) {
        return guestAuth.requestCode(request, clientIp(exchange))
                .then(Mono.just(ResponseEntity.ok(ApiResponse.<Void>success(null,
                        "If the address can sign in as a guest, a code has been sent."))));
    }

    /** The caller as the gateway passed it on (first X-Forwarded-For), else the connection; only for the captcha */
    private static String clientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        var remote = exchange.getRequest().getRemoteAddress();
        return remote != null && remote.getAddress() != null ? remote.getAddress().getHostAddress() : null;
    }

    @PostMapping("/otp/verify")
    @Operation(summary = "Check the code", description = "SIGNED_IN with tokens, or PROFILE_REQUIRED with a profile token for a new guest")
    public Mono<ResponseEntity<ApiResponse<GuestDtos.SignInResponse>>> verifyCode(@Valid @RequestBody GuestDtos.OtpVerifyRequest request) {
        return guestAuth.verifyCode(request).map(answer -> ResponseEntity.ok(ApiResponse.success(answer)));
    }

    @PostMapping("/profile")
    @Operation(summary = "Name, surname, phone and consent of a new guest", description = "Creates the guest and signs them in")
    public Mono<ResponseEntity<ApiResponse<GuestDtos.SignInResponse>>> completeProfile(@Valid @RequestBody GuestDtos.ProfileRequest request) {
        return guestAuth.completeProfile(request).map(answer -> ResponseEntity.ok(ApiResponse.success(answer)));
    }

    @PostMapping("/refresh")
    @Operation(summary = "New tokens", description = "Rotates the refresh token")
    public Mono<ResponseEntity<ApiResponse<GuestDtos.SignInResponse>>> refresh(@Valid @RequestBody GuestDtos.RefreshRequest request) {
        return guestAuth.refresh(request).map(answer -> ResponseEntity.ok(ApiResponse.success(answer)));
    }

    @PostMapping("/logout")
    @Operation(summary = "Sign out", description = "The refresh token stops working")
    public Mono<ResponseEntity<ApiResponse<Void>>> logout(@Valid @RequestBody GuestDtos.RefreshRequest request) {
        return guestAuth.logout(request).then(Mono.just(ResponseEntity.ok(ApiResponse.<Void>success(null, "Signed out"))));
    }
}
