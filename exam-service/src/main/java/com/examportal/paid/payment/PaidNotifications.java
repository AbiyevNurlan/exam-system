package com.examportal.paid.payment;

import com.examportal.paid.exam.PaidExamRepository;
import com.examportal.paid.guest.GuestDirectory;
import com.examportal.paid.order.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * What happens after an order changed, outside its transaction. Never fails the caller: what cannot be sent is logged
 * (the order number only) and the order stays as it is.
 * - item 12: the receipt of a paid order and the notice of a refund, emailed by auth-service in Azerbaijani (order
 *   number, exam, amount, time in Baku, participant code);
 * - item 17: the guest's events (guestEvent, event) - not yet connected here.
 */
@Slf4j
@Component
public class PaidNotifications {

    private final GuestDirectory directory;
    private final PaidExamRepository exams;

    @org.springframework.beans.factory.annotation.Autowired
    public PaidNotifications(GuestDirectory directory, PaidExamRepository exams) {
        this.directory = directory;
        this.exams = exams;
    }

    /** Without anything to send to (tests of other parts) */
    public PaidNotifications() {
        this(null, null);
    }

    /** The order is PAID: a receipt */
    public Mono<Void> paid(Order order) {
        return notice("RECEIPT", order, order.paidAt());
    }

    /** The order is REFUNDED: a notice */
    public Mono<Void> refunded(Order order) {
        return notice("REFUND", order, order.refundedAt());
    }

    private Mono<Void> notice(String kind, Order order, LocalDateTime at) {
        if (directory == null) {
            return Mono.empty();
        }
        return exams.find(order.examId()).map(exam -> exam.title()).defaultIfEmpty("")
                .flatMap(title -> directory.notice(order.guestId(), new GuestDirectory.Notice(kind, order.orderNo(), title,
                        order.amountMinor(), order.currency(), at != null ? at : LocalDateTime.now())))
                .onErrorResume(error -> {
                    log.warn("{} notice of order {} not sent: {}", kind, order.orderNo(), error.getClass().getSimpleName());
                    return Mono.empty();
                });
    }

    /** An event of a guest without an order (ENTITLEMENT_GRANTED) */
    public Mono<Void> guestEvent(java.util.UUID guestId, int participantCode, String action, String details) {
        return Mono.empty();
    }

    /** An event of the guest's order (ORDER_CREATED, ORDER_PAID, ...) */
    public Mono<Void> event(Order order, String action, String details) {
        return Mono.empty();
    }
}
