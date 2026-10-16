package com.examportal.exam.repository;

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
 * B11: the exam-service statements of the participant card on 100 000 users with 300 000 attempts, 100 000
 * memberships in 1 000 groups and 200 000 audit rows (throwaway Testcontainers database). Every statement
 * reads by an index of the user (no sequential scan of a big table) and finishes in a few milliseconds.
 * The plans and times are printed (B11-EXPLAIN). Skipped without Docker.
 */
class ParticipantCardPerformanceTest {

    private static PostgreSQLContainer<?> postgres;
    private static DatabaseClient db;
    private static ParticipantCardRepository cards;
    private static MyStatsRepository stats;
    private static UUID user;
    private static UUID owner;

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
        cards = new ParticipantCardRepository(db);
        stats = new MyStatsRepository(db);

        for (String statement : List.of(
                "CREATE TABLE perf_users AS SELECT g AS n, gen_random_uuid() AS id FROM generate_series(1, 100000) g",
                "CREATE UNIQUE INDEX ON perf_users (n)",
                "CREATE TABLE perf_owners AS SELECT g AS n, gen_random_uuid() AS id FROM generate_series(1, 100) g",
                "INSERT INTO exams (title, start_time, end_time, status, created_by, show_results_immediately) "
                        + "SELECT 'Exam ' || g, now() - interval '30 days', now() + interval '30 days', 'ACTIVE', "
                        + "(SELECT id FROM perf_owners WHERE n = 1 + g % 100), true FROM generate_series(1, 500) g",
                "INSERT INTO user_groups (name, created_by, owner_id) SELECT 'Group ' || g, o.id, o.id "
                        + "FROM generate_series(1, 1000) g JOIN perf_owners o ON o.n = 1 + g % 100",
                "INSERT INTO user_group_members (group_id, user_id, source, added_by) "
                        + "SELECT (SELECT min(id) FROM user_groups) + u.n % 1000, u.id, 'ADMIN', u.id FROM perf_users u",
                "INSERT INTO exam_sessions (session_id, exam_id, user_id, start_time, status, grading_status, score, total_points, "
                        + "percentage, passed, submitted_at) "
                        + "SELECT 's' || g, (SELECT min(id) FROM exams) + g % 500, u.id, now() - (g % 1000) * interval '1 hour', 'COMPLETED', "
                        + "'NOT_REQUIRED', 1, 2, (g % 100)::numeric, g % 2 = 0, now() - (g % 1000) * interval '1 hour' + interval '30 minutes' "
                        + "FROM generate_series(1, 300000) g JOIN perf_users u ON u.n = 1 + g % 100000",
                "INSERT INTO group_audit_log (actor_id, action, group_id, target_user_id, details, created_at) "
                        + "SELECT a.id, 'MEMBER_ADDED', NULL, t.id, 'source=ADMIN', now() - g * interval '1 minute' "
                        + "FROM generate_series(1, 200000) g JOIN perf_users a ON a.n = 1 + (g * 7) % 100000 "
                        + "JOIN perf_users t ON t.n = 1 + g % 100000",
                "ANALYZE")) {
            db.sql(statement).then().block();
        }
        user = db.sql("SELECT id FROM perf_users WHERE n = 4242").map(row -> row.get("id", UUID.class)).one().block();
        owner = db.sql("SELECT g.owner_id FROM user_group_members m JOIN user_groups g ON g.id = m.group_id WHERE m.user_id = :u LIMIT 1")
                .bind("u", user).map(row -> row.get("owner_id", UUID.class)).one().block();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static String plan(String sql) {
        return String.join("\n", db.sql("EXPLAIN (ANALYZE, BUFFERS) " + sql).bind("u", user).bind("o", owner)
                .map(row -> row.get(0, String.class)).all().collectList().block());
    }

    private static double executionMs(String plan) {
        return plan.lines().filter(line -> line.startsWith("Execution Time:")).findFirst()
                .map(line -> Double.parseDouble(line.replaceAll("[^0-9.]", ""))).orElse(-1.0);
    }

    private static void check(String name, String sql) {
        String plan = plan(sql);
        double ms = executionMs(plan);
        System.out.println("B11-EXPLAIN " + name + ": " + ms + " ms\n" + plan);
        for (String table : List.of("exam_sessions", "user_group_members", "group_audit_log")) {
            assertThat(plan).as(name + " reads " + table + " by an index").doesNotContain("Seq Scan on " + table);
        }
        assertThat(ms).as(name + " execution time").isBetween(0.0, 50.0);
    }

    @Test
    void everyStatementOfTheCardUsesAnIndexOfTheUser() {
        check("member of an own group", "SELECT EXISTS (SELECT 1 FROM user_group_members m JOIN user_groups g ON g.id = m.group_id "
                + "AND g.active = true WHERE m.user_id = :u AND g.owner_id = :o)");
        check("groups", "SELECT g.id, g.name, m.source, m.created_at FROM user_group_members m JOIN user_groups g ON g.id = m.group_id "
                + "AND g.active = true WHERE m.user_id = :u AND (:o IS NOT NULL) ORDER BY lower(g.name), g.id");
        check("sessions page", "SELECT s.session_id, s.exam_id, e.title, s.status FROM exam_sessions s JOIN exams e ON e.id = s.exam_id "
                + "WHERE s.user_id = :u AND (:o IS NOT NULL) ORDER BY s.start_time DESC, s.id DESC LIMIT 20 OFFSET 0");
        check("sessions count", "SELECT COUNT(*) FROM exam_sessions s JOIN exams e ON e.id = s.exam_id AND e.created_by = :o WHERE s.user_id = :u");
        check("events", "SELECT id FROM ((SELECT * FROM group_audit_log WHERE actor_id = :u ORDER BY created_at DESC, id DESC LIMIT 20) "
                + "UNION (SELECT * FROM group_audit_log WHERE target_user_id = :u AND (:o IS NOT NULL) ORDER BY created_at DESC, id DESC LIMIT 20)) "
                + "AS mine ORDER BY created_at DESC, id DESC LIMIT 20");

        // The repository calls themselves, end to end over R2DBC
        long started = System.nanoTime();
        assertThat(cards.isMemberOfGroupOwnedBy(user, owner).block()).isTrue();
        assertThat(cards.findGroups(user, null).block()).hasSize(1);
        assertThat(cards.findSessions(user, null, 0, 20).block()).hasSize(3);
        assertThat(cards.countSessions(user, null).block()).isEqualTo(3);
        assertThat(cards.findEvents(user, 20).block()).isNotEmpty();
        // The three attempts (g = 4241, 104241, 204241) are at the same exam (g % 500 = 241): one exam taken
        assertThat(stats.forUser(user, null, LocalDateTime.now()).block().getExamsTaken()).isEqualTo(1);
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.println("B11-EXPLAIN card statements end to end: " + ms + " ms");
        assertThat(ms).isLessThan(2000);
    }
}
