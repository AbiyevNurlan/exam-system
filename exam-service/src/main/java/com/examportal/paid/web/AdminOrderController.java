package com.examportal.paid.web;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.security.UserPrincipal;
import com.examportal.paid.payment.RefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * B13b items 10 and 11: the administrator's orders (ADMIN only, by path in SecurityConfig and here)
 */
@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Orders (admin)", description = "Orders, payments and refunds of paid open exams")
public class AdminOrderController {

    private final RefundService refunds;

    @PostMapping("/{id}/refund")
    @Operation(summary = "Refund an order", description = "PAID, REFUND_PENDING or NEEDS_REVIEW; a reason is required; 409 "
            + "ATTEMPT_STARTED when the guest has started the exam, unless override (with the reason). REFUNDED and the entitlement revoked.")
    public Mono<ApiResponse<String>> refund(@PathVariable Long id, @Valid @RequestBody PaidDtos.AdminRefundRequest request,
                                            @AuthenticationPrincipal UserPrincipal principal) {
        return refunds.refundByAdmin(principal.getId(), id, request.reason(), Boolean.TRUE.equals(request.override()))
                .map(order -> ApiResponse.success(order.status().name()));
    }
}
