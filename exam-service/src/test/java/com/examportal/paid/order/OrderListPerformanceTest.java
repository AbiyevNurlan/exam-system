package com.examportal.paid.order;

import com.examportal.paid.PaidTestDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B13b item 14: the administrator's list of orders with 100 000 orders. For the usual questions (newest first; by
 * status; by the 7-digit code; by exam) EXPLAIN ANALYZE shows an index scan of orders (never a sequential scan of the
 * whole table) and each page takes well under the limit below. Skipped without Docker.
 */
class OrderListPerformanceTest {

    private static PaidTestDatabase database;
    private static long examId;

    @BeforeAll
    static void start() {
        database = PaidTestDatabase.start();
        examId = database.paidExam("T-b13b perf", 1000, null);
        long other = database.paidExam("T-b13b perf other", 1000, null);
        database.db.sql("""
                INSERT INTO orders (order_no, guest_id, guest_code, exam_id, amount_minor, currency, status, provider, created_at, expires_at)
                SELECT 'P-' || n, gen_random_uuid(), 1000000 + (n % 900000), CASE WHEN n % 10 = 0 THEN :exam ELSE :other END, 1000, 'AZN',
                       (ARRAY['PAID','EXPIRED','REFUNDED','FAILED','NEEDS_REVIEW','CANCELLED'])[1 + n % 6], 'SANDBOX',
                       LOCALTIMESTAMP - make_interval(mins => n), LOCALTIMESTAMP - make_interval(mins => n) + interval '30 minutes'
                FROM generate_series(1, 100000) n
                """).bind("exam", examId).bind("other", other).then().block();
        database.db.sql("ANALYZE orders").then().block();
    }

    @AfterAll
    static void stop() {
        if (database != null) {
            database.close();
        }
    }

    private static List<String> explain(AdminOrderRepository.Filter filter, String sortBy) {
        Map<String, Object> params = new LinkedHashMap<>();
        String sql = "EXPLAIN (ANALYZE, BUFFERS) " + AdminOrderRepository.sql(AdminOrderRepository.where(filter, params),
                AdminOrderRepository.orderBy(sortBy, "desc"));
        var spec = database.db.sql(sql).bind("limit", 20).bind("offset", 0L);
        for (Map.Entry<String, Object> param : params.entrySet()) {
            spec = spec.bind(param.getKey(), param.getValue());
        }
        return spec.map(row -> row.get(0, String.class)).all().collectList().block();
    }

    private static double milliseconds(List<String> plan) {
        return plan.stream().filter(line -> line.startsWith("Execution Time"))
                .map(line -> Double.parseDouble(line.replaceAll("[^0-9.]", ""))).findFirst().orElseThrow();
    }

    @Test
    void theUsualListsUseAnIndexAndAreFast() {
        assertThat(database.scalar("SELECT count(*) FROM orders")).isEqualTo(100_000);
        Map<String, List<String>> plans = new LinkedHashMap<>();
        plans.put("newest", explain(new AdminOrderRepository.Filter(null, null, null, null, null, null), "createdAt"));
        plans.put("by status", explain(new AdminOrderRepository.Filter("NEEDS_REVIEW", null, null, null, null, null), "createdAt"));
        plans.put("by code", explain(new AdminOrderRepository.Filter(null, null, null, null, 1_000_123, null), "createdAt"));
        plans.put("by exam and status", explain(new AdminOrderRepository.Filter("PAID", examId, null, null, null, null), "createdAt"));
        plans.put("by order number", explain(new AdminOrderRepository.Filter(null, null, null, null, null, "P-777"), "createdAt"));
        plans.forEach((name, plan) -> {
            String text = String.join("\n", plan);
            assertThat(text).as(name + "\n" + text).doesNotContain("Seq Scan on orders");
            assertThat(milliseconds(plan)).as(name + "\n" + text).isLessThan(150);
        });
        // The count of a filter for the pages
        long started = System.nanoTime();
        new AdminOrderRepository(database.db).count(new AdminOrderRepository.Filter("PAID", examId, null, null, null, null)).block();
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(500);
    }
}
