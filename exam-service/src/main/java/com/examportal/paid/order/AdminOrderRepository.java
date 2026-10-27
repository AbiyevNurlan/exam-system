package com.examportal.paid.order;

import com.examportal.paid.PaidErrors;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * B13b item 11: the administrator's list of orders, filtered, sorted and paged in SQL. Only fixed text from the
 * switches below goes into a statement; every value is bound. Every order ends with the id, so no row repeats or goes
 * missing between pages; each sort has an index of V24 (created_at, status+created_at, guest_code+created_at).
 */
@Repository
@RequiredArgsConstructor
public class AdminOrderRepository {

    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_EXPORT_ROWS = 100_000;

    private final DatabaseClient databaseClient;

    /** status, examId, created from (inclusive) and to (exclusive), the guest's 7-digit code, the order number */
    public record Filter(String status, Long examId, LocalDateTime from, LocalDateTime to, Integer guestCode, String orderNo) {
    }

    public record Row(Order order, String examTitle) {
    }

    static String orderBy(String sortBy, String dir) {
        String direction = dir == null || dir.isBlank() ? "desc" : dir.trim().toLowerCase(Locale.ROOT);
        if (!direction.equals("asc") && !direction.equals("desc")) {
            throw PaidErrors.invalidFilter("dir", "must be asc or desc");
        }
        String d = direction.toUpperCase(Locale.ROOT);
        String sort = sortBy == null ? "createdat" : sortBy.trim().toLowerCase(Locale.ROOT);
        return switch (sort) {
            case "createdat", "" -> "o.created_at " + d + ", o.id " + d;
            case "paidat" -> "o.paid_at " + d + " NULLS LAST, o.id " + d;
            case "amount" -> "o.amount_minor " + d + ", o.id " + d;
            case "status" -> "o.status " + d + ", o.created_at DESC, o.id DESC";
            case "orderno" -> "o.order_no " + d;
            case "code" -> "o.guest_code " + d + ", o.created_at DESC, o.id DESC";
            default -> throw PaidErrors.invalidFilter("sortBy", "must be createdAt, paidAt, amount, status, orderNo or code");
        };
    }

    static String where(Filter filter, Map<String, Object> params) {
        StringBuilder where = new StringBuilder(" WHERE true");
        if (filter.status() != null && !filter.status().isBlank()) {
            try {
                params.put("status", OrderStatus.valueOf(filter.status().trim().toUpperCase(Locale.ROOT)).name());
            } catch (IllegalArgumentException unknown) {
                throw PaidErrors.invalidFilter("status", "is not an order status");
            }
            where.append(" AND o.status = :status");
        }
        if (filter.examId() != null) {
            params.put("examId", filter.examId());
            where.append(" AND o.exam_id = :examId");
        }
        if (filter.from() != null) {
            params.put("from", filter.from());
            where.append(" AND o.created_at >= :from");
        }
        if (filter.to() != null) {
            params.put("to", filter.to());
            where.append(" AND o.created_at < :to");
        }
        if (filter.guestCode() != null) {
            if (filter.guestCode() < 1_000_000 || filter.guestCode() > 9_999_999) {
                throw PaidErrors.invalidFilter("code", "must be 7 digits");
            }
            params.put("code", filter.guestCode());
            where.append(" AND o.guest_code = :code");
        }
        if (filter.orderNo() != null && !filter.orderNo().isBlank()) {
            params.put("orderNo", filter.orderNo().trim().toUpperCase(Locale.ROOT));
            where.append(" AND o.order_no = :orderNo");
        }
        return where.toString();
    }

    public Mono<Long> count(Filter filter) {
        return Mono.defer(() -> {
            Map<String, Object> params = new LinkedHashMap<>();
            String where = where(filter, params);
            return bind(databaseClient.sql("SELECT count(*) FROM orders o" + where), params).map(row -> row.get(0, Long.class)).one();
        });
    }

    public Flux<Row> page(Filter filter, String sortBy, String dir, int limit, long offset) {
        return Flux.defer(() -> {
            Map<String, Object> params = new LinkedHashMap<>();
            String where = where(filter, params);
            String order = orderBy(sortBy, dir);
            return bind(databaseClient.sql(sql(where, order)), params)
                    .bind("limit", limit)
                    .bind("offset", offset)
                    .map(row -> new Row(OrderRepository.order(row), row.get("exam_title", String.class)))
                    .all();
        });
    }

    /** The statement of a page, also for the EXPLAIN of the performance test */
    public static String sql(String where, String order) {
        return "SELECT " + OrderRepository.COLUMNS + ", e.title AS exam_title FROM orders o JOIN exams e ON e.id = o.exam_id"
                + where + " ORDER BY " + order + " LIMIT :limit OFFSET :offset";
    }

    private static DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec spec, Map<String, Object> params) {
        for (Map.Entry<String, Object> param : params.entrySet()) {
            spec = spec.bind(param.getKey(), param.getValue());
        }
        return spec;
    }

    /** The figures of one paid exam, in one statement */
    public record Summary(long paidCount, long revenueMinor, long refundedCount, long refundedMinor, long refundPendingCount,
                          long needsReviewCount, long pendingCount) {
    }

    public Mono<Summary> summary(Long examId) {
        return databaseClient.sql("""
                        SELECT count(*) FILTER (WHERE status = 'PAID') AS paid_count,
                               coalesce(sum(amount_minor) FILTER (WHERE status = 'PAID'), 0) AS revenue,
                               count(*) FILTER (WHERE status = 'REFUNDED') AS refunded_count,
                               coalesce(sum(amount_minor) FILTER (WHERE status = 'REFUNDED'), 0) AS refunded,
                               count(*) FILTER (WHERE status = 'REFUND_PENDING') AS refund_pending,
                               count(*) FILTER (WHERE status = 'NEEDS_REVIEW') AS needs_review,
                               count(*) FILTER (WHERE status = 'PENDING') AS pending
                        FROM orders WHERE exam_id = :exam
                        """)
                .bind("exam", examId)
                .map(row -> new Summary(row.get("paid_count", Long.class), ((Number) row.get("revenue")).longValue(),
                        row.get("refunded_count", Long.class), ((Number) row.get("refunded")).longValue(),
                        row.get("refund_pending", Long.class), row.get("needs_review", Long.class), row.get("pending", Long.class)))
                .one();
    }
}
