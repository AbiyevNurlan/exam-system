package com.examportal.auth.repository;

import com.examportal.common.domain.dto.internal.ParticipantQuery;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B11: the auth-service statements of the participant card and the profile filters on 100 000 accounts,
 * 50 000 profiles and 100 000 account events (throwaway Testcontainers database). The card reads by key and
 * by the indexes of admin_audit_log; the filtered participant page stays well under a second. Plans and
 * times are printed (B11-EXPLAIN). Skipped without Docker.
 */
class ParticipantCardPerformanceTest {

    private static PostgreSQLContainer<?> postgres;
    private static DatabaseClient db;
    private static UUID user;

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
        for (String statement : List.of(
                "INSERT INTO users (email, password_hash, first_name, last_name, role, email_verified, created_at) "
                        + "SELECT 'perf' || g || '@x.test', 'x', 'First' || g, 'Last' || g, 'STUDENT', true, now() - g * interval '1 minute' "
                        + "FROM generate_series(1, 100000) g",
                "INSERT INTO participant_profiles (user_id, category, institution, updated_by) "
                        + "SELECT id, (ARRAY['STUDENT','UNIVERSITY_STUDENT','SCHOOL_STUDENT','EMPLOYEE','INTERN','OTHER'])[1 + abs(hashtext(email)) % 6], "
                        + "'Institution ' || (abs(hashtext(email)) % 500), id FROM users WHERE abs(hashtext(email)) % 2 = 0",
                "CREATE TABLE perf_n AS SELECT row_number() OVER (ORDER BY email) AS n, id FROM users",
                "CREATE UNIQUE INDEX ON perf_n (n)",
                // 100 000 account events: an administrator's profile change of every account, by 100 different actors
                "INSERT INTO admin_audit_log (admin_user_id, action, target_user_id, details, created_at) "
                        + "SELECT a.id, 'PARTICIPANT_PROFILE_UPDATED', t.id, 'city: - -> ''Baku''', now() - g * interval '1 second' "
                        + "FROM generate_series(1, 100000) g JOIN perf_n a ON a.n = 1 + g % 100 JOIN perf_n t ON t.n = g",
                "ANALYZE")) {
            db.sql(statement).then().block();
        }
        user = db.sql("SELECT id FROM users WHERE email = 'perf4242@x.test'").map(row -> row.get("id", UUID.class)).one().block();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static String plan(String sql) {
        return String.join("\n", db.sql("EXPLAIN (ANALYZE, BUFFERS) " + sql).bind("u", user)
                .map(row -> row.get(0, String.class)).all().collectList().block());
    }

    private static double executionMs(String plan) {
        return plan.lines().filter(line -> line.startsWith("Execution Time:")).findFirst()
                .map(line -> Double.parseDouble(line.replaceAll("[^0-9.]", ""))).orElse(-1.0);
    }

    @Test
    void theCardReadsByKeyAndIndexAndTheFilterStaysFast() {
        for (String[] statement : List.of(
                new String[]{"account", "SELECT id, participant_code, first_name FROM users WHERE id = :u"},
                new String[]{"profile", "SELECT category FROM participant_profiles p WHERE p.user_id = :u"},
                new String[]{"account events", "SELECT id FROM admin_audit_log WHERE admin_user_id = :u OR target_user_id = :u "
                        + "ORDER BY created_at DESC NULLS LAST, id DESC LIMIT 20"})) {
            String plan = plan(statement[1]);
            double ms = executionMs(plan);
            System.out.println("B11-EXPLAIN " + statement[0] + ": " + ms + " ms\n" + plan);
            assertThat(plan).as(statement[0]).doesNotContain("Seq Scan on users").doesNotContain("Seq Scan on admin_audit_log")
                    .doesNotContain("Seq Scan on participant_profiles");
            assertThat(ms).as(statement[0]).isBetween(0.0, 20.0);
        }

        ParticipantQueryRepository participants = new ParticipantQueryRepository(db);
        long started = System.nanoTime();
        ParticipantQuery.PageResponse page = participants.findPage(ParticipantQuery.PageRequest.builder()
                // Profiles exist for even hashes only, so their categories are STUDENT, SCHOOL_STUDENT and INTERN
                .filter(ParticipantQuery.Filter.builder().category("SCHOOL_STUDENT").institution("Institution 12").build())
                .page(0).size(25).sort("createdAt").ascending(false).build()).block();
        long filterMs = (System.nanoTime() - started) / 1_000_000;
        System.out.println("B11-EXPLAIN participant page by category and institution: " + filterMs + " ms, " + page.getTotal() + " match");
        assertThat(page.getTotal()).isPositive();
        assertThat(filterMs).isLessThan(1000);

        ParticipantCardRepository cards = new ParticipantCardRepository(db);
        started = System.nanoTime();
        assertThat(cards.findAccount(user).block().getParticipantCode()).isBetween(1_000_000, 9_999_999);
        assertThat(cards.findAccountEvents(user, 20).block()).isNotEmpty();
        long cardMs = (System.nanoTime() - started) / 1_000_000;
        System.out.println("B11-EXPLAIN account part of the card end to end: " + cardMs + " ms");
        assertThat(cardMs).isLessThan(1000);
    }
}
