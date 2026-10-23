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
 * - item 17: the guest's events (guestEvent, event) into the guest's audit in auth-service.
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

    /** The order is REFUNDED: a notice, and the event */
    public Mono<Void> refunded(Order order) {
        return notice("REFUND", order, order.refundedAt()).then(event(order, "ORDER_REFUNDED", null));
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

    /** An event of a guest without an order (ENTITLEMENT_GRANTED), by an administrator */
    public Mono<Void> guestEvent(java.util.UUID guestId, int participantCode, String action, String details) {
        return push(guestId, new GuestDirectory.Event(action, "SYSTEM", null, null, details), action);
    }

    /**
     * An event of the guest's order (ORDER_CREATED, ORDER_PAID, ORDER_NEEDS_REVIEW, ORDER_EXPIRED, REFUND_REQUESTED,
     * ORDER_REFUNDED, ...): the order number, exam and amount, never a token, a signature or card data
     */
    public Mono<Void> event(Order order, String action, String details) {
        String text = "order " + order.orderNo() + ", exam " + order.examId() + ", " + order.amountMinor() + " " + order.currency()
                + (details == null ? "" : ", " + details);
        String actor = action.equals("ORDER_CREATED") || action.equals("REFUND_REQUESTED") ? "GUEST" : "SYSTEM";
        return push(order.guestId(), new GuestDirectory.Event(action, actor, null, null, text), action);
    }

    private Mono<Void> push(java.util.UUID guestId, GuestDirectory.Event event, String action) {
        if (directory == null) {
            return Mono.empty();
        }
        return directory.event(guestId, event).onErrorResume(error -> {
            log.warn("Event {} of guest not recorded: {}", action, error.getClass().getSimpleName());
            return Mono.empty();
        });
    }
}
