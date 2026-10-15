package com.examportal.exam.repository;

import com.examportal.common.domain.dto.MyStatsResponse;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B10: GET /api/me/stats on a real PostgreSQL with all Flyway migrations (throwaway Testcontainers
 * database). Skipped when no Docker is available.
 */
class MyStatsRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 12, 0);

    private static PostgreSQLContainer<?> postgres;
    private static DatabaseClient db;
    private static MyStatsRepository stats;

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
        stats = new MyStatsRepository(db);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /** An exam; ended = its window is over at NOW, immediate = it shows results at once */
    private static long exam(String title, boolean ended, boolean immediate) {
        return db.sql("""
                        INSERT INTO exams (title, start_time, end_time, status, created_by, show_results_immediately)
                        VALUES (:title, :start, :end, 'ACTIVE', :owner, :immediate) RETURNING id
                        """)
                .bind("title", title)
                .bind("start", NOW.minusDays(10))
                .bind("end", ended ? NOW.minusDays(1) : NOW.plusDays(1))
                .bind("owner", UUID.randomUUID())
                .bind("immediate", immediate)
                .map(row -> row.get("id", Long.class)).one().block();
    }

    private static void session(long examId, UUID user, String status, String grading, String percentage, int hoursAgo) {
        DatabaseClient.GenericExecuteSpec insert = db.sql("""
                        INSERT INTO exam_sessions (session_id, exam_id, user_id, start_time, status, grading_status,
                                                   percentage, passed, submitted_at)
                        VALUES (:sid, :examId, :userId, :start, :status, :grading, :percentage, :passed, :submitted)
                        """)
                .bind("sid", UUID.randomUUID().toString())
                .bind("examId", examId)
                .bind("userId", user)
                .bind("start", NOW.minusHours(hoursAgo + 1))
                .bind("status", status)
                .bind("grading", grading);
        insert = percentage == null ? insert.bindNull("percentage", BigDecimal.class).bindNull("passed", Boolean.class)
                : insert.bind("percentage", new BigDecimal(percentage)).bind("passed", new BigDecimal(percentage).compareTo(BigDecimal.valueOf(50)) >= 0);
        insert = "COMPLETED".equals(status) ? insert.bind("submitted", NOW.minusHours(hoursAgo)) : insert.bindNull("submitted", LocalDateTime.class);
        insert.then().block();
    }

    private static MyStatsResponse of(UUID user) {
        return stats.forUser(user, NOW).block();
    }

    @Test
    void noAttemptsAtAll() {
        MyStatsResponse none = of(UUID.randomUUID());
        assertThat(none.getExamsTaken()).isZero();
        assertThat(none.getExamsGraded()).isZero();
        assertThat(none.getAveragePercent()).isNull();
        assertThat(none.getBestPercent()).isNull();
        assertThat(none.getLastResult()).isNull();
        assertThat(none.getPendingGradingCount()).isZero();
    }

    @Test
    void onlyAnAttemptThatWaitsForAGrader() {
        UUID user = UUID.randomUUID();
        long essay = exam("Essay", true, true);
        session(essay, user, "COMPLETED", "PENDING", "20.00", 2);

        MyStatsResponse waiting = of(user);
        assertThat(waiting.getExamsTaken()).isEqualTo(1);
        assertThat(waiting.getExamsGraded()).isZero();
        assertThat(waiting.getAveragePercent()).isNull();
        assertThat(waiting.getBestPercent()).isNull();
        assertThat(waiting.getLastResult()).isNull();
        assertThat(waiting.getPendingGradingCount()).isEqualTo(1);
    }

    @Test
    void mixedBestAttemptPerExamWithheldAndRunningLeftOut() {
        UUID user = UUID.randomUUID();
        long math = exam("Math", true, false);          // ended: visible
        long physics = exam("Physics", false, true);    // shows results at once: visible
        long chemistry = exam("Chemistry", false, false); // not ended, not immediate: withheld
        long biology = exam("Biology", true, true);
        session(math, user, "COMPLETED", "NOT_REQUIRED", "40.00", 30);
        session(math, user, "COMPLETED", "NOT_REQUIRED", "70.00", 20);   // the best of Math
        session(physics, user, "COMPLETED", "COMPLETE", "55.55", 10);    // graded by hand, most recent visible
        session(chemistry, user, "COMPLETED", "NOT_REQUIRED", "99.00", 1); // withheld: not in the figures
        session(biology, user, "COMPLETED", "PENDING", "10.00", 5);       // waits for a grader
        session(biology, user, "IN_PROGRESS", "NOT_REQUIRED", null, 0);  // running: not taken, not graded

        MyStatsResponse mixed = of(user);
        assertThat(mixed.getExamsTaken()).isEqualTo(4);
        assertThat(mixed.getExamsGraded()).isEqualTo(2);
        // (70.00 + 55.55) / 2 = 62.775 -> 62.78
        assertThat(mixed.getAveragePercent()).isEqualByComparingTo("62.78");
        assertThat(mixed.getBestPercent()).isEqualByComparingTo("70.00");
        assertThat(mixed.getPendingGradingCount()).isEqualTo(1);
        assertThat(mixed.getLastResult().getExamId()).isEqualTo(physics);
        assertThat(mixed.getLastResult().getExamTitle()).isEqualTo("Physics");
        assertThat(mixed.getLastResult().getPercentage()).isEqualByComparingTo("55.55");
        assertThat(mixed.getLastResult().getPassed()).isTrue();
        assertThat(mixed.getLastResult().getSubmittedAt()).isEqualTo(NOW.minusHours(10));
    }

    @Test
    void theAttemptsOfSomeoneElseNeverCount() {
        UUID me = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        long shared = exam("Shared", true, true);
        session(shared, other, "COMPLETED", "NOT_REQUIRED", "100.00", 3);
        session(shared, other, "COMPLETED", "PENDING", "0.00", 2);
        session(shared, me, "COMPLETED", "NOT_REQUIRED", "30.00", 1);

        MyStatsResponse mine = of(me);
        assertThat(mine.getExamsTaken()).isEqualTo(1);
        assertThat(mine.getBestPercent()).isEqualByComparingTo("30.00");
        assertThat(mine.getAveragePercent()).isEqualByComparingTo("30.00");
        assertThat(mine.getPendingGradingCount()).isZero();
    }
}
