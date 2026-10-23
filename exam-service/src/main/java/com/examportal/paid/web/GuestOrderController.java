package com.examportal.paid.web;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.paid.order.GuestOrderService;
import com.examportal.paid.security.PaidGuest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * B13b item 5: a guest's orders (GUEST only, PaidSecurityConfig and here)
 */
@RestController
@RequestMapping("/api/guest/orders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('GUEST')")
@Tag(name = "Guest orders", description = "Buying a paid open exam")
public class GuestOrderController {

    private final GuestOrderService orders;

    @PostMapping
    @Operation(summary = "Order an exam", description = "The open PENDING order of the same exam again when there is one; 409 "
            + "ALREADY_ENTITLED, SALES_CLOSED, SOLD_OUT; 403 GUEST_BLOCKED. The answer has the order number, the amount in "
            + "whole minor units and the checkoutUrl.")
    public Mono<ApiResponse<PaidDtos.OrderView>> create(@AuthenticationPrincipal PaidGuest guest,
                                                        @Valid @RequestBody PaidDtos.OrderRequest request) {
        return orders.create(guest, request.examId()).map(ApiResponse::success);
    }

    @GetMapping
    @Operation(summary = "The own orders, newest first (at most 100)")
    public Mono<ApiResponse<List<PaidDtos.OrderView>>> list(@AuthenticationPrincipal PaidGuest guest) {
        return orders.list(guest).map(ApiResponse::success);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One own order", description = "Another guest's order is the same 404 ORDER_NOT_FOUND as an unknown one")
    public Mono<ApiResponse<PaidDtos.OrderView>> get(@AuthenticationPrincipal PaidGuest guest, @PathVariable Long id) {
        return orders.get(guest, id).map(ApiResponse::success);
    }
}
