package com.examportal.paid.order;

import com.examportal.paid.PaidErrors;
import com.examportal.paid.exam.PaidExamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * B13b item 4: the atomic heart of an order, in one transaction:
 * 1. the guest and the exam take turns with other orders of the same guest for the same exam (advisory lock);
 * 2. the exam's PENDING orders whose time is over expire and give their seats back (one statement);
 * 3. the exam must be PAID_OPEN, published and on sale (409 SALES_CLOSED, 404 for anything else);
 * 4. an ACTIVE entitlement is 409 ALREADY_ENTITLED; an open PENDING order of the guest is the answer (idempotent);
 * 5. a seat is taken by one conditional UPDATE (never beyond seat_limit, 409 SOLD_OUT) and the order is written with
 *    the exam's price at this moment, expiring after the order time (30 minutes).
 * Whatever runs in parallel, never more seats than the limit (SeatReservationTest: 200 orders for 50 seats).
 */
@Component
@RequiredArgsConstructor
public class SeatReservation {

    private final PaidExamRepository exams;
    private final OrderRepository orders;
    private final EntitlementRepository entitlements;
    private final TransactionalOperator transactionalOperator;

    @Value("${payments.order-ttl-minutes:30}")
    private int orderTtlMinutes = 30;

    /** The order and whether it was made now (false: the guest's open order of before) */
    public record Reservation(Order order, boolean created) {
    }

    public Mono<Reservation> reserve(UUID guestId, int guestCode, Long examId, String provider) {
        Mono<Reservation> reserve = orders.lockGuestExam(guestId, examId)
                .then(orders.expireStale(examId).then())
                .then(exams.find(examId))
                .filter(exam -> exam.paidOpen())
                .switchIfEmpty(Mono.error(PaidErrors.examNotFound()))
                .flatMap(exam -> exam.salesOpen(LocalDateTime.now()) ? Mono.just(exam) : Mono.error(PaidErrors.salesClosed()))
                .flatMap(exam -> entitlements.hasActive(guestId, examId)
                        .flatMap(entitled -> entitled ? Mono.<Reservation>error(PaidErrors.alreadyEntitled())
                                : orders.findPending(guestId, examId).map(open -> new Reservation(open, false))
                                        .switchIfEmpty(Mono.defer(() -> exams.takeSeat(examId)
                                                .flatMap(taken -> taken
                                                        ? orders.insertPending(guestId, guestCode, examId, exam.priceMinor(), exam.currency(),
                                                                provider, orderTtlMinutes).map(order -> new Reservation(order, true))
                                                        : Mono.<Reservation>error(PaidErrors.soldOut()))))));
        return transactionalOperator.transactional(reserve);
    }
}
