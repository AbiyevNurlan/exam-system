package com.examportal.exam.history;

import com.examportal.common.domain.dto.history.ExamHistoryDtos;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B12a: the history endpoint on a large database (throwaway Testcontainers PostgreSQL with all migrations):
 * 1 000 exams with 100 000 sessions, 200 000 history rows spread over them and one busy exam with 20 000
 * rows of its own. Every statement of ExamHistoryQueryRepository reads exam_revisions by one of its indexes
 * (no sequential scan) and finishes in milliseconds, also for the busy exam, a deep page and the filters.
 * The plans and times are printed (B12A-EXPLAIN). Skipped without Docker.
 */
class ExamHistoryPerformanceTest {

    private static final int BUSY_ROWS = 20_000;

    private static PostgreSQLContainer<?> postgres;
    private static DatabaseClient db;
    private static ExamHistoryQueryRepository history;
    private static Long busyExam;
    private static UUID actor;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        db = DatabaseClient.create(new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
                .host(postgres.getHost()).port(postgres.getMappedPort(5432)).database(postgres.getDatabaseName())
                .username(postgres.getUsername()).password(postgres.getPassword()).build()));
        history = new ExamHistoryQueryRepository(db);

        for (String statement : List.of(
                "CREATE TABLE perf_people AS SELECT g AS n, gen_random_uuid() AS id FROM generate_series(1, 20000) g",
                "CREATE UNIQUE INDEX ON perf_people (n)",
                "INSERT INTO exams (title, start_time, end_time, status, created_by, show_results_immediately) "
                        + "SELECT 'Exam ' || g, now() - interval '30 days', now() + interval '30 days', 'ACTIVE', "
                        + "(SELECT id FROM perf_people WHERE n = 1 + g % 100), true FROM generate_series(1, 1000) g",
                "INSERT INTO exam_sessions (session_id, exam_id, user_id, start_time, status, grading_status, score, total_points, "
                        + "percentage, passed, submitted_at) "
                        + "SELECT 's' || g, (SELECT min(id) FROM exams) + g % 1000, p.id, now() - (g % 1000) * interval '1 hour', "
                        + "'COMPLETED', 'NOT_REQUIRED', 1, 2, (g % 100)::numeric, g % 2 = 0, now() - (g % 1000) * interval '1 hour' "
                        + "FROM generate_series(1, 100000) g JOIN perf_people p ON p.n = 1 + g % 20000",
                // 200 rows for each exam: questions edited by 100 different staff members, every fifth one sensitive
                "INSERT INTO exam_revisions (exam_id, revision_no, save_id, exam_owner_id, actor_user_id, actor_code, actor_name, "
                        + "actor_role, created_at, action, entity_type, entity_id, changes, sensitive, summary_key, summary_params) "
                        + "SELECT e.id, r, md5(e.id || '-' || (r / 3))::uuid, e.created_by, p.id, 1000000 + p.n, 'Staff ' || p.n, 'INSTRUCTOR', "
                        + "now() AT TIME ZONE 'UTC' - (200 - r) * interval '1 minute', "
                        + "CASE WHEN r % 5 = 0 THEN 'CORRECT_ANSWER_CHANGED' ELSE 'QUESTION_UPDATED' END, 'QUESTION', 1 + r % 40, "
                        + "'{\"text\": [\"before\", \"after\"]}'::jsonb, r % 5 = 0, 'QUESTION_EDITED', "
                        + "jsonb_build_object('questionNumber', 1 + r % 40) "
                        + "FROM exams e CROSS JOIN generate_series(1, 200) r JOIN perf_people p ON p.n = 1 + (e.id + r) % 100",
                "INSERT INTO exam_revision_counters (exam_id, last_revision) SELECT id, 200 FROM exams",
                "ANALYZE")) {
            db.sql(statement).then().block();
        }
        // One busy exam: 20 000 more rows on top of its 200
        busyExam = db.sql("SELECT min(id) AS id FROM exams").map(row -> row.get("id", Long.class)).one().block();
        db.sql("INSERT INTO exam_revisions (exam_id, revision_no, save_id, exam_owner_id, actor_user_id, actor_code, actor_name, "
                        + "actor_role, created_at, action, entity_type, entity_id, changes, sensitive, summary_key, summary_params) "
                        + "SELECT :exam, 200 + r, md5('busy-' || (r / 4))::uuid, e.created_by, p.id, 1000000 + p.n, 'Staff ' || p.n, "
                        + "'ADMIN', now() AT TIME ZONE 'UTC' - (" + BUSY_ROWS + " - r) * interval '1 second', "
                        + "CASE WHEN r % 7 = 0 THEN 'POINTS_CHANGED' ELSE 'QUESTION_UPDATED' END, 'QUESTION', 1 + r % 300, "
                        + "'{\"points\": [1, 2]}'::jsonb, r % 7 = 0, 'QUESTION_EDITED', '{}'::jsonb "
                        + "FROM exams e CROSS JOIN generate_series(1, " + BUSY_ROWS + ") r JOIN perf_people p ON p.n = 1 + r % 100 "
                        + "WHERE e.id = :exam")
                .bind("exam", busyExam).then().block();
        db.sql("ANALYZE exam_revisions").then().block();
        actor = db.sql("SELECT id FROM perf_people WHERE n = 7").map(row -> row.get("id", UUID.class)).one().block();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static String plan(String sql) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("EXPLAIN (ANALYZE, BUFFERS) " + sql).bind("e", busyExam);
        if (sql.contains(":a")) {
            spec = spec.bind("a", actor);
        }
        return String.join("\n", spec.map(row -> row.get(0, String.class)).all().collectList().block());
    }

    private static double executionMs(String plan) {
        return plan.lines().filter(line -> line.startsWith("Execution Time:")).findFirst()
                .map(line -> Double.parseDouble(line.replaceAll("[^0-9.]", ""))).orElse(-1.0);
    }

    private static void check(String name, String sql) {
        String plan = plan(sql);
        double ms = executionMs(plan);
        System.out.println("B12A-EXPLAIN " + name + ": " + ms + " ms\n" + plan);
        for (String table : List.of("exam_revisions", "exam_sessions")) {
            assertThat(plan).as(name + " reads " + table + " by an index").doesNotContain("Seq Scan on " + table);
        }
        assertThat(ms).as(name + " execution time").isBetween(0.0, 100.0);
    }

    @Test
    void everyHistoryStatementUsesAnIndexOfTheExam() {
        long rows = db.sql("SELECT COUNT(*) FROM exam_revisions").map(row -> row.get(0, Long.class)).one().block();
        long sessions = db.sql("SELECT COUNT(*) FROM exam_sessions").map(row -> row.get(0, Long.class)).one().block();
        assertThat(rows).isEqualTo(200_000L + BUSY_ROWS);
        assertThat(sessions).isEqualTo(100_000L);

        // The statements of ExamHistoryQueryRepository, on the busy exam
        String columns = "id, revision_no, action, created_at";
        check("first page", "SELECT " + columns + " FROM exam_revisions WHERE exam_id = :e ORDER BY revision_no DESC LIMIT 50 OFFSET 0");
        check("deep page", "SELECT " + columns + " FROM exam_revisions WHERE exam_id = :e ORDER BY revision_no DESC LIMIT 50 OFFSET 10000");
        check("count", "SELECT COUNT(*) FROM exam_revisions WHERE exam_id = :e AND (:a IS NOT NULL)");
        check("sensitive only", "SELECT " + columns + " FROM exam_revisions WHERE exam_id = :e AND sensitive AND (:a IS NOT NULL) "
                + "ORDER BY revision_no DESC LIMIT 50");
        check("by actor", "SELECT " + columns + " FROM exam_revisions WHERE exam_id = :e AND actor_user_id = :a "
                + "ORDER BY revision_no DESC LIMIT 50");
        check("by action and day", "SELECT " + columns + " FROM exam_revisions WHERE exam_id = :e AND action = 'POINTS_CHANGED' "
                + "AND created_at >= (now() AT TIME ZONE 'UTC') - interval '1 day' AND (:a IS NOT NULL) ORDER BY revision_no DESC LIMIT 50");
        check("one row", "SELECT " + columns + ", changes FROM exam_revisions WHERE exam_id = :e AND id = (SELECT max(id) FROM exam_revisions) "
                + "AND (:a IS NOT NULL)");
        check("owner of a deleted exam", "SELECT exam_owner_id FROM exam_revisions WHERE exam_id = :e AND exam_owner_id IS NOT NULL "
                + "AND (:a IS NOT NULL) ORDER BY revision_no DESC LIMIT 1");
        check("last edit of each question", "SELECT DISTINCT ON (entity_id) entity_id, actor_user_id, created_at FROM exam_revisions "
                + "WHERE exam_id = :e AND entity_type = 'QUESTION' AND entity_id IS NOT NULL AND (:a IS NOT NULL) "
                + "ORDER BY entity_id, revision_no DESC");

        // The repository itself, end to end over R2DBC, on the busy exam
        ExamHistoryQueryRepository.Filter none = new ExamHistoryQueryRepository.Filter(null, null, null, null, null, false, null);
        long started = System.nanoTime();
        List<ExamHistoryDtos.Entry> page = history.page(busyExam, none, 50, 0).collectList().block();
        assertThat(page).hasSize(50);
        assertThat(page.get(0).getRevisionNo()).isEqualTo(200 + BUSY_ROWS);
        assertThat(history.count(busyExam, none).block()).isEqualTo(200L + BUSY_ROWS);
        ExamHistoryQueryRepository.Filter sensitive = new ExamHistoryQueryRepository.Filter(null, null, null, null, null, true, "QUESTION");
        assertThat(history.page(busyExam, sensitive, 50, 0).collectList().block()).allMatch(entry -> Boolean.TRUE.equals(entry.getSensitive()));
        ExamHistoryQueryRepository.Filter byCode = new ExamHistoryQueryRepository.Filter(null, 1000007, "QUESTION_UPDATED",
                LocalDateTime.now().minusDays(2), LocalDateTime.now().plusDays(1), false, null);
        assertThat(history.page(busyExam, byCode, 50, 0).collectList().block())
                .isNotEmpty().allMatch(entry -> entry.getActor().getCode() == 1000007);
        assertThat(history.lastQuestionEdits(busyExam).block()).hasSize(300);
        assertThat(history.owner(busyExam).block()).isNotNull();
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.println("B12A-EXPLAIN history statements end to end: " + ms + " ms");
        assertThat(ms).isLessThan(3000);
    }
}
