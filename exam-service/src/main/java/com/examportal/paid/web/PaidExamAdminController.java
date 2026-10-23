package com.examportal.paid.web;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.history.ChangeContextFactory;
import com.examportal.paid.exam.PaidExamSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * B13b item 1: the paid settings of an exam, ADMIN only (by path in SecurityConfig, /api/admin/**, and here)
 */
@RestController
@RequestMapping("/api/admin/paid-exams")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Paid exams (admin)", description = "Settings of paid open exams")
public class PaidExamAdminController {

    private final PaidExamSettingsService settings;
    private final ChangeContextFactory changes;
    private final com.examportal.paid.payment.RefundService refunds;

    @GetMapping("/{id}/settings")
    @Operation(summary = "The paid settings of an exam")
    public Mono<ApiResponse<PaidDtos.SettingsResponse>> getSettings(@PathVariable Long id) {
        return settings.get(id).map(found -> ApiResponse.success(PaidDtos.SettingsResponse.of(found)));
    }

    @org.springframework.web.bind.annotation.PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a paid open exam", description = "CANCELLED in its history; open orders cancelled, every paid order "
            + "refunded, every entitlement revoked. A reason is required.")
    public Mono<ApiResponse<com.examportal.paid.payment.RefundService.CancelSummary>> cancel(@PathVariable Long id,
                                                                                         @Valid @RequestBody PaidDtos.CancelRequest request,
                                                                                         ServerHttpRequest httpRequest,
                                                                                         @AuthenticationPrincipal UserPrincipal principal) {
        return changes.create(principal, httpRequest, request.reason())
                .flatMap(change -> refunds.cancelExam(id, request.reason(), change))
                .map(ApiResponse::success);
    }

    @PutMapping("/{id}/settings")
    @Operation(summary = "Change the paid settings", description = "PAID_OPEN: priceMinor > 0 (whole qəpik), the sales window "
            + "ending before the start; the access mode only without attempts (409 EXAM_HAS_SESSIONS) and, back to AUDIENCE, "
            + "without open orders (409 PAID_EXAM_HAS_ORDERS). Orders keep their own price. Written into the exam's history.")
    public Mono<ApiResponse<PaidDtos.SettingsResponse>> updateSettings(@PathVariable Long id,
                                                                       @Valid @RequestBody PaidDtos.SettingsRequest request,
                                                                       ServerHttpRequest httpRequest,
                                                                       @AuthenticationPrincipal UserPrincipal principal) {
        return changes.create(principal, httpRequest, request.changeReason())
                .flatMap(change -> settings.update(id, request, change))
                .map(saved -> ApiResponse.success(PaidDtos.SettingsResponse.of(saved)));
    }
}
