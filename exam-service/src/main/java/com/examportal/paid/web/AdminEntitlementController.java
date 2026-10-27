package com.examportal.paid.web;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.security.UserPrincipal;
import com.examportal.paid.order.AdminOrderService;
import com.examportal.paid.order.EntitlementRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * B13b item 11: free access to a paid open exam, ADMIN only, with a reason
 */
@RestController
@RequestMapping("/api/admin/entitlements")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Entitlements (admin)", description = "Free access to paid open exams")
public class AdminEntitlementController {

    private final AdminOrderService admin;

    @PostMapping
    @Operation(summary = "Give a guest free access", description = "examId, guestId or the 7-digit participantCode, reason (required), "
            + "attempts (1); 409 ALREADY_ENTITLED, 404 for an exam that is not PAID_OPEN or an unknown guest")
    public Mono<ApiResponse<EntitlementRepository.Entitlement>> grant(@Valid @RequestBody PaidDtos.GrantRequest request,
                                                                      @AuthenticationPrincipal UserPrincipal principal) {
        return admin.grant(principal.getId(), request.examId(), request.guestId(), request.participantCode(), request.reason(), request.attempts())
                .map(ApiResponse::success);
    }
}
