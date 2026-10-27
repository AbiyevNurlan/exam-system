package com.examportal.paid.payment;

import com.examportal.paid.PaidErrors;
import com.examportal.paid.order.EntitlementRepository;
import com.examportal.paid.order.Order;
import com.examportal.paid.order.OrderRepository;
import com.examportal.paid.order.OrderStatus;
import com.examportal.paid.provider.PaymentProvider;
import com.examportal.paid.provider.PaymentProviders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * B13b items 7 and 10: giving money back, the one way every refund takes (an automatic one after a payment that
 * cannot be honoured, the administrator's, the cancellation of an exam):
 * 1. the provider of the order is asked to refund (MANUAL: the administrator gave the money back outside);
 * 2. SUCCEEDED: in one transaction the order becomes REFUNDED, its entitlement REVOKED, its seat given back, and a
 *    payment event REFUND_SUCCEEDED is written; PENDING: the order waits in REFUND_PENDING for the provider's
 *    notification; FAILED: nothing changes (502), the order stays for a new try.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderRefunds {

    private final OrderRepository orders;
    private final EntitlementRepository entitlements;
    private final PaymentEventRepository events;
    private final PaymentProviders providers;
    private final TransactionalOperator transactionalOperator;
    private final PaidNotifications notifications;

    public Mono<Order> refund(Long orderId, String reason) {
        return orders.find(orderId)
                .switchIfEmpty(Mono.error(PaidErrors.orderNotFound()))
                .flatMap(order -> {
                    PaymentProvider provider = providers.byName(order.provider()).orElse(null);
                    if (provider == null) {
                        return Mono.error(PaidErrors.providerUnavailable());
                    }
                    return provider.refund(order, reason)
                            .onErrorResume(error -> {
                                log.warn("Refund of order {} at {} failed: {}", order.orderNo(), provider.name(), error.getClass().getSimpleName());
                                return Mono.just(PaymentProvider.RefundOutcome.FAILED);
                            })
                            .flatMap(outcome -> {
                                Mono<Order> next;
                                if (outcome == PaymentProvider.RefundOutcome.SUCCEEDED) {
                                    next = completeRefund(orderId, reason, provider.name(), "refund-" + UUID.randomUUID())
                                            .flatMap(refunded -> notifications.refunded(refunded).thenReturn(refunded));
                                } else if (outcome == PaymentProvider.RefundOutcome.PENDING) {
                                    next = transactionalOperator.transactional(orders.lock(orderId)
                                            .flatMap(locked -> locked.status() == OrderStatus.REFUND_PENDING ? Mono.just(locked)
                                                    : orders.move(orderId, OrderStatus.REFUND_PENDING, reason)));
                                } else {
                                    next = Mono.error(PaidErrors.providerError());
                                }
                                return next;
                            });
                });
    }

    /**
     * The money is back: REFUNDED, entitlement REVOKED, seat given back, an event written; part of no transaction
     * (makes its own). An order refunded already stays as it is.
     */
    public Mono<Order> completeRefund(Long orderId, String reason, String provider, String eventId) {
        return transactionalOperator.transactional(orders.lock(orderId)
                .flatMap(order -> order.status() == OrderStatus.REFUNDED ? Mono.just(order) : applyRefund(order, reason, provider, eventId)));
    }

    /** The same inside the caller's transaction, the order locked by the caller */
    public Mono<Order> applyRefund(Order order, String reason, String provider, String eventId) {
        if (!order.status().canMoveTo(OrderStatus.REFUNDED)) {
            return Mono.error(PaidErrors.transitionNotAllowed(order.status().name(), OrderStatus.REFUNDED.name()));
        }
        return orders.move(order.id(), OrderStatus.REFUNDED, reason)
                .flatMap(refunded -> entitlements.revokeForOrder(order.id())
                        .then(orders.releaseSeat(order.id()))
                        .then(events.insert(order.id(), provider, eventId, "REFUND_SUCCEEDED", true, order.amountMinor(), order.currency(),
                                "REFUNDED", null))
                        .thenReturn(refunded));
    }
}
