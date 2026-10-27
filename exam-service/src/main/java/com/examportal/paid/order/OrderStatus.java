package com.examportal.paid.order;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * B13b item 3: the statuses of an order and the only directions it may move in. The same table is enforced by the
 * database (trigger trg_orders_status_transition, V24); OrderStatusMachineTest checks that the two agree on every pair.
 *
 *   PENDING ──► PAID ──► REFUND_PENDING ──► REFUNDED
 *      │          └──────────────────────────► REFUNDED
 *      │                       ▲   └──► PAID (refund refused)
 *      ├──► FAILED ──┐         │
 *      ├──► EXPIRED ─┼──► NEEDS_REVIEW ──► PAID | REFUND_PENDING | REFUNDED
 *      ├──► CANCELLED┘         ▲
 *      └───────────────────────┘
 */
public enum OrderStatus {
    PENDING, PAID, FAILED, EXPIRED, CANCELLED, REFUND_PENDING, REFUNDED, NEEDS_REVIEW;

    private static final Map<OrderStatus, Set<OrderStatus>> NEXT = Map.of(
            PENDING, EnumSet.of(PAID, FAILED, EXPIRED, CANCELLED, NEEDS_REVIEW),
            FAILED, EnumSet.of(NEEDS_REVIEW),
            EXPIRED, EnumSet.of(NEEDS_REVIEW),
            CANCELLED, EnumSet.of(NEEDS_REVIEW),
            PAID, EnumSet.of(REFUND_PENDING, REFUNDED),
            REFUND_PENDING, EnumSet.of(REFUNDED, PAID),
            NEEDS_REVIEW, EnumSet.of(PAID, REFUND_PENDING, REFUNDED),
            REFUNDED, EnumSet.noneOf(OrderStatus.class));

    /** Whether an order in this status may move to next (staying is always allowed) */
    public boolean canMoveTo(OrderStatus next) {
        return this == next || NEXT.get(this).contains(next);
    }

    /** The statuses an order may still be paid or changed in (it holds a seat or is being decided) */
    public boolean isOpen() {
        return this == PENDING || this == PAID || this == REFUND_PENDING || this == NEEDS_REVIEW;
    }
}
