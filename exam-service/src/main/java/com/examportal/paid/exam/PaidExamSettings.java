package com.examportal.paid.exam;

import com.examportal.common.domain.entity.ExamStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The paid settings of one exam (V25 columns of exams) with what the checks need from the exam itself.
 * Money is in whole minor units (qəpik), never a fraction.
 */
public record PaidExamSettings(
        Long examId,
        String title,
        ExamStatus status,
        UUID ownerId,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String accessMode,
        Integer priceMinor,
        String currency,
        Integer seatLimit,
        int seatsTaken,
        LocalDateTime salesOpenAt,
        LocalDateTime salesCloseAt,
        int refundDeadlineHours,
        int attemptsPerPurchase) {

    public static final String AUDIENCE = "AUDIENCE";
    public static final String PAID_OPEN = "PAID_OPEN";

    public boolean paidOpen() {
        return PAID_OPEN.equals(accessMode);
    }

    /** Published (SCHEDULED or ACTIVE), PAID_OPEN and inside the sales window */
    public boolean salesOpen(LocalDateTime now) {
        return paidOpen() && (status == ExamStatus.SCHEDULED || status == ExamStatus.ACTIVE)
                && salesOpenAt != null && salesCloseAt != null && !now.isBefore(salesOpenAt) && now.isBefore(salesCloseAt);
    }

    /** null without a limit */
    public Integer seatsLeft() {
        return seatLimit == null ? null : Math.max(0, seatLimit - seatsTaken);
    }
}
