package com.examportal.paid.exam;

import com.examportal.common.domain.entity.ExamStatus;
import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The paid columns of exams (V25). The only place that writes them: the Exam entity reads access_mode only
 * (@ReadOnlyProperty), so no other save can change a paid setting. Seats are taken and given back by conditional
 * UPDATEs, never by reading and writing back.
 */
@Repository
@RequiredArgsConstructor
public class PaidExamRepository {

    static final String COLUMNS = "e.id, e.title, e.status, e.created_by, e.start_time, e.end_time, e.access_mode, e.price_minor, "
            + "e.currency, e.seat_limit, e.seats_taken, e.sales_open_at, e.sales_close_at, e.refund_deadline_hours, e.attempts_per_purchase";

    private final DatabaseClient databaseClient;

    public Mono<PaidExamSettings> find(Long examId) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM exams e WHERE e.id = :id AND e.active IS DISTINCT FROM false")
                .bind("id", examId)
                .map(PaidExamRepository::settings)
                .one();
    }

    /** The same, with the exam row locked until the end of the transaction */
    public Mono<PaidExamSettings> lock(Long examId) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM exams e WHERE e.id = :id AND e.active IS DISTINCT FROM false FOR UPDATE")
                .bind("id", examId)
                .map(PaidExamRepository::settings)
                .one();
    }

    public Mono<Boolean> hasSessions(Long examId) {
        return databaseClient.sql("SELECT EXISTS (SELECT 1 FROM exam_sessions WHERE exam_id = :id)")
                .bind("id", examId)
                .map(row -> Boolean.TRUE.equals(row.get(0, Boolean.class)))
                .one();
    }

    /** Writes the paid settings; part of the caller's transaction */
    public Mono<PaidExamSettings> save(Long examId, String accessMode, Integer priceMinor, String currency, Integer seatLimit,
                                       LocalDateTime salesOpenAt, LocalDateTime salesCloseAt, int refundDeadlineHours,
                                       int attemptsPerPurchase) {
        DatabaseClient.GenericExecuteSpec update = databaseClient.sql("UPDATE exams e SET access_mode = :mode, price_minor = :price, "
                        + "currency = :currency, seat_limit = :seatLimit, sales_open_at = :open, sales_close_at = :close, "
                        + "refund_deadline_hours = :refundHours, attempts_per_purchase = :attempts, updated_at = LOCALTIMESTAMP "
                        + "WHERE e.id = :id RETURNING " + COLUMNS)
                .bind("id", examId)
                .bind("mode", accessMode)
                .bind("currency", currency)
                .bind("refundHours", refundDeadlineHours)
                .bind("attempts", attemptsPerPurchase);
        update = priceMinor == null ? update.bindNull("price", Integer.class) : update.bind("price", priceMinor);
        update = seatLimit == null ? update.bindNull("seatLimit", Integer.class) : update.bind("seatLimit", seatLimit);
        update = salesOpenAt == null ? update.bindNull("open", LocalDateTime.class) : update.bind("open", salesOpenAt);
        update = salesCloseAt == null ? update.bindNull("close", LocalDateTime.class) : update.bind("close", salesCloseAt);
        return update.map(PaidExamRepository::settings).one();
    }

    /**
     * Takes one seat when one is left (or without a limit): true when taken. One conditional UPDATE, so parallel
     * orders can never take more seats than the limit.
     */
    public Mono<Boolean> takeSeat(Long examId) {
        return databaseClient.sql("UPDATE exams SET seats_taken = seats_taken + 1 "
                        + "WHERE id = :id AND (seat_limit IS NULL OR seats_taken < seat_limit)")
                .bind("id", examId)
                .fetch().rowsUpdated()
                .map(updated -> updated == 1);
    }

    /** Gives seats back (never below zero); part of the caller's transaction */
    public Mono<Void> releaseSeats(Long examId, int count) {
        if (count <= 0) {
            return Mono.empty();
        }
        return databaseClient.sql("UPDATE exams SET seats_taken = GREATEST(0, seats_taken - :count) WHERE id = :id")
                .bind("id", examId)
                .bind("count", count)
                .then();
    }

    /** Sets the status of a paid exam to CANCELLED; part of the caller's transaction */
    public Mono<Void> cancel(Long examId) {
        return databaseClient.sql("UPDATE exams SET status = 'CANCELLED', updated_at = LOCALTIMESTAMP WHERE id = :id")
                .bind("id", examId)
                .then();
    }

    /** The public catalog: PAID_OPEN, published, sales open now, by start */
    public Flux<PaidExamSettings> findOnSale(LocalDateTime now, int limit, long offset) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM exams e " + ON_SALE + " ORDER BY e.start_time, e.id LIMIT :limit OFFSET :offset")
                .bind("now", now)
                .bind("limit", limit)
                .bind("offset", offset)
                .map(PaidExamRepository::settings)
                .all();
    }

    public Mono<Long> countOnSale(LocalDateTime now) {
        return databaseClient.sql("SELECT count(*) FROM exams e " + ON_SALE)
                .bind("now", now)
                .map(row -> row.get(0, Long.class))
                .one();
    }

    /** One exam of the catalog: the same conditions */
    public Mono<PaidExamSettings> findOnSale(Long examId, LocalDateTime now) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM exams e " + ON_SALE + " AND e.id = :id")
                .bind("now", now)
                .bind("id", examId)
                .map(PaidExamRepository::settings)
                .one();
    }

    public Mono<String> description(Long examId) {
        return databaseClient.sql("SELECT coalesce(description, '') FROM exams WHERE id = :id")
                .bind("id", examId)
                .map(row -> row.get(0, String.class))
                .one();
    }

    public Mono<Integer> durationMinutes(Long examId) {
        return databaseClient.sql("SELECT duration_minutes FROM exams WHERE id = :id")
                .bind("id", examId)
                .map(row -> row.get(0, Integer.class))
                .one();
    }

    static final String ON_SALE = "WHERE e.access_mode = 'PAID_OPEN' AND e.status IN ('SCHEDULED', 'ACTIVE') "
            + "AND e.active IS DISTINCT FROM false AND e.sales_open_at <= :now AND e.sales_close_at > :now";

    static PaidExamSettings settings(Readable row) {
        String status = row.get("status", String.class);
        return new PaidExamSettings(
                row.get("id", Long.class),
                row.get("title", String.class),
                status == null ? null : ExamStatus.valueOf(status),
                row.get("created_by", UUID.class),
                row.get("start_time", LocalDateTime.class),
                row.get("end_time", LocalDateTime.class),
                row.get("access_mode", String.class),
                row.get("price_minor", Integer.class),
                row.get("currency", String.class),
                row.get("seat_limit", Integer.class),
                row.get("seats_taken", Integer.class),
                row.get("sales_open_at", LocalDateTime.class),
                row.get("sales_close_at", LocalDateTime.class),
                row.get("refund_deadline_hours", Integer.class),
                row.get("attempts_per_purchase", Integer.class));
    }
}
