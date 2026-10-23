package com.examportal.paid.exam;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Whether an exam still has orders that are not closed (PENDING, PAID, REFUND_PENDING, NEEDS_REVIEW): such an exam
 * stays PAID_OPEN, so nobody loses what they paid for by a change of the access mode
 */
@Component
@RequiredArgsConstructor
public class PaidOrderGuard {

    private final DatabaseClient databaseClient;

    public Mono<Boolean> hasOpenOrders(Long examId) {
        return databaseClient.sql("SELECT EXISTS (SELECT 1 FROM orders WHERE exam_id = :id "
                        + "AND status IN ('PENDING', 'PAID', 'REFUND_PENDING', 'NEEDS_REVIEW'))")
                .bind("id", examId)
                .map(row -> Boolean.TRUE.equals(row.get(0, Boolean.class)))
                .one();
    }
}
