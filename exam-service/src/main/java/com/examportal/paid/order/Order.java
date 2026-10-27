package com.examportal.paid.order;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row of orders (V24). Money in whole minor units.
 */
public record Order(
        Long id,
        String orderNo,
        UUID guestId,
        int guestCode,
        Long examId,
        int amountMinor,
        String currency,
        OrderStatus status,
        String provider,
        String providerRef,
        boolean seatHeld,
        String reviewReason,
        String refundReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime expiresAt,
        LocalDateTime paidAt,
        LocalDateTime refundRequestedAt,
        LocalDateTime refundedAt) {
}
