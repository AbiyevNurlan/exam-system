package com.examportal.paid.payment;

import com.examportal.common.domain.entity.ExamStatus;
import com.examportal.paid.PaidErrors;
import com.examportal.paid.exam.PaidExamRepository;
import com.examportal.paid.order.EntitlementRepository;
import com.examportal.paid.order.Order;
import com.examportal.paid.order.OrderRepository;
import com.examportal.paid.order.OrderStatus;
import com.examportal.paid.provider.PaymentProvider;
import com.examportal.paid.provider.PaymentProviders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * B13b item 7: a provider's notification (POST /api/payments/webhook/{provider}).
 *
 * - The signature is checked over the raw body: a bad one is written as an event (signature_valid false) and
 *   answered 401 SIGNATURE_INVALID; nothing else happens.
 * - One transaction per notification: the order row is locked, the effect applied, and the event written last with
 *   ON CONFLICT DO NOTHING on the provider's event id: a notification seen before rolls its effect back and is answered
 *   200 DUPLICATE, so the same notification twice (also at the same time) changes nothing.
 * - PAYMENT_SUCCEEDED of a PENDING order whose amount and currency match: PAID, an ACTIVE entitlement and the event, in
 *   the same transaction. Amount or currency wrong: NEEDS_REVIEW. A payment for an order that expired, was cancelled or
 *   failed, for a cancelled exam, or for a guest who may take the exam already: NEEDS_REVIEW and, after the commit, an
 *   automatic refund (OrderRefunds).
 * - PAYMENT_FAILED of a PENDING order: FAILED and the seat given back. REFUND_SUCCEEDED: REFUNDED (OrderRefunds).
 * The browser coming back from the provider never decides anything: its page only asks the order's status.
 * Log lines carry the provider, the order number and the outcome, never the body or the signature.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentWebhookService {

    private final PaymentProviders providers;
    private final OrderRepository orders;
    private final EntitlementRepository entitlements;
    private final PaidExamRepository exams;
    private final PaymentEventRepository events;
    private final OrderRefunds refunds;
    private final PaidNotifications notifications;
    private final TransactionalOperator transactionalOperator;

    public enum Outcome {
        PAID, NEEDS_REVIEW_AMOUNT, NEEDS_REVIEW_LATE, NEEDS_REVIEW_EXAM_CANCELLED, NEEDS_REVIEW_ALREADY_ENTITLED, FAILED, REFUNDED,
        DUPLICATE, ORDER_UNKNOWN, IGNORED, DUPLICATE_PAYMENT
    }

    /** What was decided and whether an automatic refund follows */
    public record Result(Outcome outcome, Long orderId, boolean refundFollows) {
    }

    private static final class DuplicateEvent extends RuntimeException {
        DuplicateEvent() {
            super("duplicate event", null, false, false);
        }
    }

    public Mono<Result> handle(String providerName, byte[] body, HttpHeaders headers) {
        PaymentProvider provider = providers.byName(providerName).orElse(null);
        // A provider hidden here (the sandbox in production) is unknown: 404, and no event is written
        if (provider == null || provider.hidden()) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
        PaymentProvider.Verification verification = provider.verifyWebhook(body, headers);
        if (!verification.valid()) {
            log.warn("Payment notification of {} refused: signature not valid", provider.name());
            return events.insert(null, provider.name(), null, "UNVERIFIED", false, null, null, "SIGNATURE_INVALID", verification.payload())
                    .then(Mono.error(PaidErrors.signatureInvalid()));
        }
        return process(provider.name(), verification.event(), verification.payload());
    }

    /** A verified notification (also the reconcile's answer of a provider) */
    public Mono<Result> process(String provider, PaymentProvider.WebhookEvent event, String payload) {
        Mono<Result> work = orders.lockByNo(event.orderNo())
                .flatMap(order -> apply(order, event)
                        .flatMap(result -> events.insert(order.id(), provider, event.eventId(), event.type().name(), true, event.amountMinor(),
                                        event.currency(), result.outcome().name(), payload)
                                .switchIfEmpty(Mono.error(new DuplicateEvent()))
                                .thenReturn(result)))
                .switchIfEmpty(Mono.defer(() -> events.insert(null, provider, event.eventId(), event.type().name(), true, event.amountMinor(),
                                event.currency(), Outcome.ORDER_UNKNOWN.name(), payload)
                        .map(id -> new Result(Outcome.ORDER_UNKNOWN, null, false))
                        .switchIfEmpty(Mono.error(new DuplicateEvent()))));
        return transactionalOperator.transactional(work)
                .onErrorResume(DuplicateEvent.class, duplicate -> Mono.just(new Result(Outcome.DUPLICATE, null, false)))
                .flatMap(result -> after(result))
                .doOnNext(result -> log.info("Payment notification of {} for {}: {}", provider, event.orderNo(), result.outcome()));
    }

    /** The effect of one notification on the locked order; nothing is written here but the order and the entitlement */
    private Mono<Result> apply(Order order, PaymentProvider.WebhookEvent event) {
        return switch (event.type()) {
            case PAYMENT_SUCCEEDED -> paymentSucceeded(order, event);
            case PAYMENT_FAILED -> order.status() == OrderStatus.PENDING
                    ? orders.move(order.id(), OrderStatus.FAILED, null).then(orders.releaseSeat(order.id()))
                    .thenReturn(new Result(Outcome.FAILED, order.id(), false))
                    : Mono.just(new Result(Outcome.IGNORED, order.id(), false));
            case REFUND_SUCCEEDED -> order.status().canMoveTo(OrderStatus.REFUNDED) && order.status() != OrderStatus.REFUNDED
                    ? orders.move(order.id(), OrderStatus.REFUNDED, "refund confirmed by the provider")
                    .then(entitlements.revokeForOrder(order.id()))
                    .then(orders.releaseSeat(order.id()))
                    .thenReturn(new Result(Outcome.REFUNDED, order.id(), false))
                    : Mono.just(new Result(Outcome.IGNORED, order.id(), false));
        };
    }

    private Mono<Result> paymentSucceeded(Order order, PaymentProvider.WebhookEvent event) {
        boolean matches = event.amountMinor() != null && event.amountMinor() == order.amountMinor()
                && order.currency().equalsIgnoreCase(String.valueOf(event.currency()));
        if (order.status() == OrderStatus.PAID) {
            // A second payment (another event id) for an order paid already: kept as an event for the administrator
            return Mono.just(new Result(Outcome.DUPLICATE_PAYMENT, order.id(), false));
        }
        if (!matches) {
            return order.status().canMoveTo(OrderStatus.NEEDS_REVIEW) && order.status() != OrderStatus.NEEDS_REVIEW
                    ? orders.move(order.id(), OrderStatus.NEEDS_REVIEW, "AMOUNT_MISMATCH").thenReturn(new Result(Outcome.NEEDS_REVIEW_AMOUNT, order.id(), false))
                    : Mono.just(new Result(Outcome.IGNORED, order.id(), false));
        }
        if (order.status() == OrderStatus.EXPIRED || order.status() == OrderStatus.CANCELLED || order.status() == OrderStatus.FAILED) {
            return orders.move(order.id(), OrderStatus.NEEDS_REVIEW, "LATE_PAYMENT_" + order.status().name())
                    .thenReturn(new Result(Outcome.NEEDS_REVIEW_LATE, order.id(), true));
        }
        if (order.status() != OrderStatus.PENDING) {
            return Mono.just(new Result(Outcome.IGNORED, order.id(), false));
        }
        return exams.find(order.examId())
                .flatMap(exam -> {
                    if (exam.status() == ExamStatus.CANCELLED) {
                        return orders.move(order.id(), OrderStatus.NEEDS_REVIEW, "EXAM_CANCELLED")
                                .thenReturn(new Result(Outcome.NEEDS_REVIEW_EXAM_CANCELLED, order.id(), true));
                    }
                    return entitlements.grantForOrder(order, exam.attemptsPerPurchase())
                            .flatMap(granted -> orders.move(order.id(), OrderStatus.PAID, null).thenReturn(new Result(Outcome.PAID, order.id(), false)))
                            .switchIfEmpty(Mono.defer(() -> orders.move(order.id(), OrderStatus.NEEDS_REVIEW, "ALREADY_ENTITLED")
                                    .thenReturn(new Result(Outcome.NEEDS_REVIEW_ALREADY_ENTITLED, order.id(), true))));
                });
    }

    /** After the commit: the receipt of a paid order, the automatic refund of a payment that cannot be honoured */
    private Mono<Result> after(Result result) {
        if (result.orderId() == null) {
            return Mono.just(result);
        }
        Mono<Void> follow = Mono.empty();
        if (result.outcome() == Outcome.PAID) {
            follow = orders.find(result.orderId()).flatMap(order -> notifications.paid(order)
                    .then(notifications.event(order, "ORDER_PAID", null)));
        } else if (result.refundFollows()) {
            follow = orders.find(result.orderId()).flatMap(order -> notifications.event(order, "ORDER_NEEDS_REVIEW", result.outcome().name()))
                    .then(refunds.refund(result.orderId(), "automatic: " + result.outcome().name())
                    .onErrorResume(error -> {
                        log.warn("Automatic refund of order {} not done: {}", result.orderId(), error.getClass().getSimpleName());
                        return Mono.empty();
                    }))
                    .then();
        } else if (result.outcome() == Outcome.NEEDS_REVIEW_AMOUNT) {
            follow = orders.find(result.orderId()).flatMap(order -> notifications.event(order, "ORDER_NEEDS_REVIEW", result.outcome().name()));
        } else if (result.outcome() == Outcome.REFUNDED) {
            follow = orders.find(result.orderId()).flatMap(notifications::refunded);
        }
        return follow.onErrorResume(error -> Mono.empty()).thenReturn(result);
    }
}
