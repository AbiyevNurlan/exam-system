package com.examportal.paid.web;

import com.examportal.paid.exam.PaidExamSettings;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * The request and answer bodies of paid open exams (B13b). Money is always a whole number of minor units
 * (priceMinor, amountMinor: qəpik) with its currency; never a fraction.
 */
public final class PaidDtos {

    private PaidDtos() {
    }

    /** PUT /api/admin/paid-exams/{id}/settings; changeReason goes into the exam's history (B12a) */
    public record SettingsRequest(
            String accessMode,
            Integer priceMinor,
            @Size(min = 3, max = 3) String currency,
            Integer seatLimit,
            LocalDateTime salesOpenAt,
            LocalDateTime salesCloseAt,
            Integer refundDeadlineHours,
            Integer attemptsPerPurchase,
            @Size(max = 500) String changeReason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SettingsResponse(
            Long examId,
            String title,
            String status,
            String accessMode,
            Integer priceMinor,
            String currency,
            Integer seatLimit,
            int seatsTaken,
            LocalDateTime salesOpenAt,
            LocalDateTime salesCloseAt,
            int refundDeadlineHours,
            int attemptsPerPurchase) {

        public static SettingsResponse of(PaidExamSettings settings) {
            return new SettingsResponse(settings.examId(), settings.title(), settings.status() == null ? null : settings.status().name(),
                    settings.accessMode(), settings.priceMinor(), settings.currency(), settings.seatLimit(), settings.seatsTaken(),
                    settings.salesOpenAt(), settings.salesCloseAt(), settings.refundDeadlineHours(), settings.attemptsPerPurchase());
        }
    }

    /**
     * One exam of the public catalog: what a visitor needs to decide, nothing else (no question, answer, owner, audience
     * or internal field). seatsLeft is null when the exam has no seat limit.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CatalogExam(Long id, String title, String description, LocalDateTime startTime, LocalDateTime endTime,
                              Integer durationMinutes, int priceMinor, String currency, Integer seatsLeft,
                              LocalDateTime salesCloseAt) {
    }

    public record CatalogPage(java.util.List<CatalogExam> content, long totalElements, int page, int size) {
    }

    /** POST /api/guest/orders */
    public record OrderRequest(@jakarta.validation.constraints.NotNull Long examId) {
    }

    /**
     * An order as its guest sees it. checkoutUrl only while it is PENDING (where to pay); the status page of the browser
     * asks this again and again, and only the provider's notification ever changes the status.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrderView(Long id, String orderNo, Long examId, String examTitle, int amountMinor, String currency, String status,
                            LocalDateTime createdAt, LocalDateTime expiresAt, LocalDateTime paidAt, LocalDateTime refundRequestedAt,
                            LocalDateTime refundedAt, String checkoutUrl, LocalDateTime examStartsAt, LocalDateTime examEndsAt) {

        public static OrderView of(com.examportal.paid.order.Order order, String examTitle, String checkoutUrl) {
            return of(order, examTitle, checkoutUrl, null, null);
        }

        /** examStartsAt and examEndsAt: the exam's window (UTC, as every time here), so the guest knows when to come back */
        public static OrderView of(com.examportal.paid.order.Order order, String examTitle, String checkoutUrl,
                                   LocalDateTime examStartsAt, LocalDateTime examEndsAt) {
            return new OrderView(order.id(), order.orderNo(), order.examId(), examTitle, order.amountMinor(), order.currency(),
                    order.status().name(), order.createdAt(), order.expiresAt(), order.paidAt(), order.refundRequestedAt(),
                    order.refundedAt(), checkoutUrl, examStartsAt, examEndsAt);
        }
    }

    /** POST /api/admin/orders/{id}/refund: reason 3-500 characters; override when the guest has started the exam */
    public record AdminRefundRequest(@jakarta.validation.constraints.NotBlank @Size(min = 3, max = 500) String reason, Boolean override) {
    }

    /** POST /api/admin/paid-exams/{id}/cancel */
    public record CancelRequest(@jakarta.validation.constraints.NotBlank @Size(min = 3, max = 500) String reason) {
    }

    // ---------- the administrator's orders (item 11) ----------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminOrderRow(Long id, String orderNo, int participantCode, java.util.UUID guestId, Long examId, String examTitle,
                                int amountMinor, String currency, String status, String provider, LocalDateTime createdAt,
                                LocalDateTime expiresAt, LocalDateTime paidAt, LocalDateTime refundRequestedAt, LocalDateTime refundedAt) {

        public static AdminOrderRow of(com.examportal.paid.order.Order order, String examTitle) {
            return new AdminOrderRow(order.id(), order.orderNo(), order.guestCode(), order.guestId(), order.examId(), examTitle,
                    order.amountMinor(), order.currency(), order.status().name(), order.provider(), order.createdAt(), order.expiresAt(),
                    order.paidAt(), order.refundRequestedAt(), order.refundedAt());
        }
    }

    public record AdminOrderPage(java.util.List<AdminOrderRow> content, long totalElements, int page, int size) {
    }

    /** One order with its payment events in time order (provider notifications and administrators' actions) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminOrderDetail(AdminOrderRow order, String providerRef, String reviewReason, String refundReason,
                                   java.util.List<com.examportal.paid.payment.PaymentEventRepository.PaymentEvent> events,
                                   java.util.List<com.examportal.paid.order.EntitlementRepository.Entitlement> entitlements) {
    }

    /** POST /api/admin/orders/{id}/mark-paid (MANUAL: cash or bank transfer) */
    public record MarkPaidRequest(@jakarta.validation.constraints.NotBlank @Size(min = 3, max = 500) String reason,
                                  @jakarta.validation.constraints.NotBlank @Size(max = 100) String documentNo) {
    }

    /** POST /api/admin/entitlements: free access to a paid open exam, by guestId or the 7-digit code */
    public record GrantRequest(@jakarta.validation.constraints.NotNull Long examId, java.util.UUID guestId, Integer participantCode,
                               @jakarta.validation.constraints.NotBlank @Size(min = 3, max = 500) String reason, Integer attempts) {
    }

    /** The figures of a paid exam, money in whole minor units */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExamSummary(Long examId, String title, String currency, long paidCount, long revenueMinor, long refundedCount,
                              long refundedMinor, long refundPendingCount, long needsReviewCount, long pendingCount, Integer seatLimit,
                              int seatsTaken) {
    }
}
