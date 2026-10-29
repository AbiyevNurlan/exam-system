package com.examportal.paid.web;

import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.security.UserPrincipal;
import com.examportal.paid.order.AdminOrderRepository;
import com.examportal.paid.order.AdminOrderService;
import com.examportal.paid.payment.RefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;

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
    private final AdminOrderService admin;

    private static AdminOrderRepository.Filter filter(String status, Long examId, LocalDateTime from, LocalDateTime to, Integer code,
                                                      String orderNo, Boolean demo) {
        return new AdminOrderRepository.Filter(status, examId, from, to, code, orderNo, demo);
    }

    @GetMapping
    @Operation(summary = "Orders, filtered and sorted", description = "status, examId, from/to (created, UTC), code (7 digits), orderNo, "
            + "demo (true: only DEMO orders, false: only real ones); sortBy createdAt, paidAt, amount, status, orderNo, code; dir asc/desc; pages of at most 100")
    public Mono<ApiResponse<PaidDtos.AdminOrderPage>> list(@RequestParam(required = false) String status,
                                                           @RequestParam(required = false) Long examId,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                                           @RequestParam(required = false) Integer code,
                                                           @RequestParam(required = false) String orderNo,
                                                           @RequestParam(required = false) Boolean demo,
                                                           @RequestParam(required = false) String sortBy,
                                                           @RequestParam(required = false) String dir,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        return admin.page(filter(status, examId, from, to, code, orderNo, demo), sortBy, dir, page, size).map(ApiResponse::success);
    }

    @GetMapping("/export")
    @Operation(summary = "The orders of a filter as CSV", description = "UTF-8 with a BOM, comma separated, Azerbaijani headers, the "
            + "participant code, times in Baku; at most 100 000 rows")
    public Mono<ResponseEntity<Flux<DataBuffer>>> export(@RequestParam(required = false) String status,
                                                         @RequestParam(required = false) Long examId,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                                         @RequestParam(required = false) Integer code,
                                                         @RequestParam(required = false) String orderNo,
                                                         @RequestParam(required = false) Boolean demo,
                                                         @RequestParam(required = false) String sortBy,
                                                         @RequestParam(required = false) String dir,
                                                         @AuthenticationPrincipal UserPrincipal principal) {
        return admin.csv(filter(status, examId, from, to, code, orderNo, demo), sortBy, dir, principal.getId())
                .map(body -> ResponseEntity.ok()
                        .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sifarisler-" + LocalDate.now() + ".csv\"")
                        .body(body));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One order with its payment events in time order")
    public Mono<ApiResponse<PaidDtos.AdminOrderDetail>> detail(@PathVariable Long id) {
        return admin.detail(id).map(ApiResponse::success);
    }

    @PostMapping("/{id}/mark-paid")
    @Operation(summary = "Record a payment outside the system (MANUAL)", description = "Cash or bank transfer: reason and the document's "
            + "number required; PENDING or NEEDS_REVIEW becomes PAID with its entitlement")
    public Mono<ApiResponse<String>> markPaid(@PathVariable Long id, @Valid @RequestBody PaidDtos.MarkPaidRequest request,
                                              @AuthenticationPrincipal UserPrincipal principal) {
        return admin.markPaid(principal.getId(), id, request.reason(), request.documentNo()).map(order -> ApiResponse.success(order.status().name()));
    }

    @PostMapping("/{id}/refund")
    @Operation(summary = "Refund an order", description = "PAID, REFUND_PENDING or NEEDS_REVIEW; a reason is required; 409 "
            + "ATTEMPT_STARTED when the guest has started the exam, unless override (with the reason). REFUNDED and the entitlement revoked.")
    public Mono<ApiResponse<String>> refund(@PathVariable Long id, @Valid @RequestBody PaidDtos.AdminRefundRequest request,
                                            @AuthenticationPrincipal UserPrincipal principal) {
        return refunds.refundByAdmin(principal.getId(), id, request.reason(), Boolean.TRUE.equals(request.override()))
                .map(order -> ApiResponse.success(order.status().name()));
    }
}
