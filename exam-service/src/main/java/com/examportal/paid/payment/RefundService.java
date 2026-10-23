package com.examportal.paid.payment;

import com.examportal.common.domain.entity.ExamStatus;
import com.examportal.exam.history.ChangeContext;
import com.examportal.exam.history.ExamHistoryService;
import com.examportal.exam.history.RevisionDraft;
import com.examportal.paid.PaidErrors;
import com.examportal.paid.exam.PaidExamRepository;
import com.examportal.paid.order.Order;
import com.examportal.paid.order.OrderRepository;
import com.examportal.paid.order.OrderStatus;
import com.examportal.paid.security.PaidGuest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * B13b item 10: refunds.
 * - The guest asks: only a PAID order of the own, before the exam's refund deadline (refund_deadline_hours before the
 *   start) and without an attempt started: REFUND_PENDING (the administrator does the refund).
 * - The administrator refunds (a reason is required): PAID, REFUND_PENDING or NEEDS_REVIEW; when the guest has started the
 *   exam, 409 ATTEMPT_STARTED unless override with the reason. The provider gives the money back (OrderRefunds):
 *   REFUNDED and the entitlement REVOKED.
 * - An exam cancelled by the administrator (paid open exams only): CANCELLED in its history, every PENDING order
 *   CANCELLED with its seat back, every paid one refunded, every entitlement of it revoked.
 * Every action of an administrator is also an event of the order (ADMIN_..., append only).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    public static final int MIN_REASON = 3;
    public static final int MAX_REASON = 500;

    private final OrderRepository orders;
    private final PaidExamRepository exams;
    private final PaymentEventRepository events;
    private final OrderRefunds refunds;
    private final ExamHistoryService history;
    private final PaidNotifications notifications;
    private final DatabaseClient databaseClient;
    private final TransactionalOperator transactionalOperator;

    public record CancelSummary(Long examId, int cancelledOrders, int refunded, int refundsPending, int refundsFailed) {
    }

    static String reason(String reason) {
        String trimmed = reason == null ? "" : reason.strip();
        if (trimmed.length() < MIN_REASON || trimmed.length() > MAX_REASON) {
            throw PaidErrors.reasonRequired();
        }
        return trimmed;
    }

    public Mono<Boolean> attemptStarted(Order order) {
        return databaseClient.sql("SELECT EXISTS (SELECT 1 FROM exam_sessions WHERE exam_id = :exam AND user_id = :guest)")
                .bind("exam", order.examId())
                .bind("guest", order.guestId())
                .map(row -> Boolean.TRUE.equals(row.get(0, Boolean.class)))
                .one();
    }

    /** The guest's request: REFUND_PENDING, or 409 REFUND_NOT_ALLOWED with why */
    public Mono<Order> requestByGuest(PaidGuest guest, Long orderId) {
        Mono<Order> request = orders.findOwn(orderId, guest.id())
                .switchIfEmpty(Mono.error(PaidErrors.orderNotFound()))
                .flatMap(own -> orders.lock(own.id()))
                .flatMap(order -> {
                    if (order.status() != OrderStatus.PAID) {
                        return Mono.error(PaidErrors.refundNotAllowed("Only a paid order can be refunded"));
                    }
                    return exams.find(order.examId()).flatMap(exam -> {
                        if (exam.startTime() == null || !LocalDateTime.now().isBefore(exam.startTime().minusHours(exam.refundDeadlineHours()))) {
                            return Mono.error(PaidErrors.refundNotAllowed("The refund deadline (" + exam.refundDeadlineHours()
                                    + " hours before the start) is over"));
                        }
                        return attemptStarted(order).flatMap(started -> started
                                ? Mono.<Order>error(PaidErrors.refundNotAllowed("The exam has been started"))
                                : orders.move(order.id(), OrderStatus.REFUND_PENDING, "requested by the guest"));
                    });
                });
        return transactionalOperator.transactional(request)
                .flatMap(order -> notifications.event(order, "REFUND_REQUESTED", null).thenReturn(order));
    }

    /** The administrator's refund */
    public Mono<Order> refundByAdmin(UUID adminId, Long orderId, String reasonText, boolean override) {
        return Mono.defer(() -> {
            String reason = reason(reasonText);
            return orders.find(orderId)
                    .switchIfEmpty(Mono.error(PaidErrors.orderNotFound()))
                    .flatMap(order -> {
                        if (order.status() != OrderStatus.PAID && order.status() != OrderStatus.REFUND_PENDING
                                && order.status() != OrderStatus.NEEDS_REVIEW) {
                            return Mono.error(PaidErrors.transitionNotAllowed(order.status().name(), OrderStatus.REFUNDED.name()));
                        }
                        return attemptStarted(order).flatMap(started -> started && !override
                                ? Mono.<Order>error(PaidErrors.attemptStarted())
                                : adminEvent(order, "ADMIN_REFUND", adminId, reason, started ? "override: attempt started" : null)
                                .then(refunds.refund(order.id(), reason)));
                    });
        });
    }

    /** Cancels a paid open exam and gives every buyer the money back */
    public Mono<CancelSummary> cancelExam(Long examId, String reasonText, ChangeContext change) {
        return Mono.defer(() -> {
            String reason = reason(reasonText);
            Mono<List<Order>> cancel = exams.lock(examId)
                    .switchIfEmpty(Mono.error(PaidErrors.examNotFound()))
                    .flatMap(exam -> {
                        if (!exam.paidOpen()) {
                            return Mono.error(PaidErrors.examNotFound());
                        }
                        if (exam.status() == ExamStatus.CANCELLED || exam.status() == ExamStatus.ARCHIVED || exam.status() == ExamStatus.COMPLETED) {
                            return Mono.error(PaidErrors.examClosed());
                        }
                        Map<String, Object> changes = new LinkedHashMap<>();
                        changes.put("status", java.util.Arrays.asList(exam.status().name(), "CANCELLED"));
                        Map<String, Object> params = new LinkedHashMap<>();
                        params.put("statusBefore", exam.status().name());
                        params.put("status", "CANCELLED");
                        return exams.cancel(examId)
                                .then(history.record(examId, exam.ownerId(), exam.status(), change.withReason(reason),
                                        List.of(new RevisionDraft("CANCELLED", RevisionDraft.EXAM, examId, changes, true, "EXAM_CANCELLED", params))))
                                .then(databaseClient.sql("UPDATE entitlements SET status = 'REVOKED', updated_at = LOCALTIMESTAMP "
                                        + "WHERE exam_id = :exam AND status = 'ACTIVE' AND order_id IS NULL").bind("exam", examId).then())
                                .thenMany(orders.findPaidOfExam(examId))
                                .concatMap(order -> order.status() == OrderStatus.PENDING
                                        ? orders.move(order.id(), OrderStatus.CANCELLED, null).flatMap(cancelled -> orders.releaseSeat(order.id()).thenReturn(cancelled))
                                        : Mono.just(order))
                                .collectList();
                    });
            return transactionalOperator.transactional(cancel)
                    .flatMap(affected -> Flux.fromIterable(affected)
                            .filter(order -> order.status() != OrderStatus.CANCELLED)
                            .concatMap(order -> adminEvent(order, "ADMIN_EXAM_CANCELLED", change.actorId(), reason, null)
                                    .then(refunds.refund(order.id(), "exam cancelled: " + reason))
                                    .map(refunded -> refunded.status() == OrderStatus.REFUNDED ? "REFUNDED" : "PENDING")
                                    .onErrorResume(error -> {
                                        log.warn("Refund of order {} after the cancellation failed: {}", order.orderNo(), error.getClass().getSimpleName());
                                        return Mono.just("FAILED");
                                    }))
                            .collectList()
                            .map(results -> new CancelSummary(examId,
                                    (int) affected.stream().filter(order -> order.status() == OrderStatus.CANCELLED).count(),
                                    (int) results.stream().filter("REFUNDED"::equals).count(),
                                    (int) results.stream().filter("PENDING"::equals).count(),
                                    (int) results.stream().filter("FAILED"::equals).count())));
        });
    }

    /** An administrator's action as an event of the order (append only) */
    Mono<Long> adminEvent(Order order, String type, UUID adminId, String reason, String note) {
        String payload = "{\"adminId\":\"" + adminId + "\",\"reason\":" + json(reason) + (note == null ? "" : ",\"note\":" + json(note)) + "}";
        return events.insert(order.id(), "ADMIN", "admin-" + UUID.randomUUID(), type, true, order.amountMinor(), order.currency(), type, payload);
    }

    static String json(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
    }
}
