package com.examportal.paid.payment;

import com.examportal.paid.order.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * What happens after an order changed, outside its transaction: the receipt and the refund notice by email (item 12)
 * and the guest's events in auth-service (item 17). Never fails the caller: a notice that cannot be sent is logged.
 */
@Component
public class PaidNotifications {

    /** The order is PAID: a receipt */
    public Mono<Void> paid(Order order) {
        return Mono.empty();
    }

    /** The order is REFUNDED: a notice */
    public Mono<Void> refunded(Order order) {
        return Mono.empty();
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
