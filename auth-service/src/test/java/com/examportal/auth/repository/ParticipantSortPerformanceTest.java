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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B11b: every order of the participant list on 100 000 participants (and 2 000 other accounts, 50 000
 * profiles; Azerbaijani names, some registration times NULL) on a throwaway Testcontainers database. A page
 * is read from its V7 index in both directions, also deep in the list, without a sort of all participants
 * and without a sequential scan; with a profile filter the page stays well under a second. Plans and times
 * are printed (B11b-EXPLAIN). Skipped without Docker.
 */
class ParticipantSortPerformanceTest {

    private static PostgreSQLContainer<?> postgres;
    private static DatabaseClient db;

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
        String firstNames = "(ARRAY['Əli','Çingiz','Ğəni','Həsən','Xanım','Işıq','İlqar','Ömər','Şəkər','Üzeyir','Aydın','Leyla','Nərmin','Kamran','Qasım','Zaur'])";
        String lastNames = "(ARRAY['Əliyev','Məmmədova','Hüseynov','Quliyev','Şirinova','İsmayılov','Çələbi','Ağayev','Babayeva','Zeynalov'])";
        for (String statement : List.of(
                "INSERT INTO users (email, password_hash, first_name, last_name, role, email_verified, created_at) "
                        + "SELECT 'sort' || g || '@x.test', 'x', " + firstNames + "[1 + g % 16] || CASE WHEN g % 7 = 0 THEN ' ' ELSE '' END, "
                        + lastNames + "[1 + (g / 16) % 10], 'STUDENT', true, "
                        + "CASE WHEN g % 1000 = 0 THEN NULL ELSE now() - g * interval '1 minute' END "
                        + "FROM generate_series(1, 100000) g",
                "INSERT INTO users (email, password_hash, first_name, last_name, role, email_verified) "
                        + "SELECT 'staff' || g || '@x.test', 'x', 'Staff', 'Member', 'INSTRUCTOR', true FROM generate_series(1, 2000) g",
                "INSERT INTO participant_profiles (user_id, category, institution, updated_by) "
                        + "SELECT id, (ARRAY['STUDENT','UNIVERSITY_STUDENT','SCHOOL_STUDENT','EMPLOYEE','INTERN','OTHER'])[1 + abs(hashtext(email)) % 6], "
                        + "'Institution ' || (abs(hashtext(email)) % 500), id FROM users WHERE abs(hashtext(email)) % 2 = 0 AND role = 'STUDENT'",
                "ANALYZE")) {
            db.sql(statement).then().block();
        }
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static String plan(String sql) {
        return String.join("\n", db.sql("EXPLAIN (ANALYZE, BUFFERS) " + sql)
                .map(row -> row.get(0, String.class)).all().collectList().block());
    }

    private static double executionMs(String plan) {
        return plan.lines().filter(line -> line.startsWith("Execution Time:")).findFirst()
                .map(line -> Double.parseDouble(line.replaceAll("[^0-9.]", ""))).orElse(-1.0);
    }

    @Test
    void everyOrderReadsItsIndexInBothDirections() {
        for (String key : List.of("code", "firstName", "lastName", "email", "registeredAt")) {
            for (boolean ascending : List.of(true, false)) {
                for (int offset : List.of(0, 5000)) {
                    String sql = "SELECT id, email, participant_code, first_name, last_name, created_at FROM users "
                            + "WHERE role = 'STUDENT' ORDER BY " + ParticipantQueryRepository.orderBy(key, ascending)
                            + " LIMIT 25 OFFSET " + offset;
                    String plan = plan(sql);
                    double ms = executionMs(plan);
                    String name = key + (ascending ? " asc" : " desc") + " offset " + offset;
                    System.out.println("B11b-EXPLAIN " + name + ": " + ms + " ms\n" + plan);
                    assertThat(plan).as(name).contains("Index Scan").doesNotContain("Seq Scan on users")
                            .doesNotContainPattern("(?m)^\\s*(->\\s*)?Sort ");
                    assertThat(ms).as(name).isBetween(0.0, 100.0);
                }
            }
        }

        ParticipantQueryRepository participants = new ParticipantQueryRepository(db);
        for (String key : List.of("lastName", "firstName", "registeredAt")) {
            long started = System.nanoTime();
            ParticipantQuery.PageResponse page = participants.findPage(ParticipantQuery.PageRequest.builder()
                    .filter(ParticipantQuery.Filter.builder().category("SCHOOL_STUDENT").institution("Institution 12").build())
                    .page(0).size(25).sort(key).ascending(false).build()).block();
            long ms = (System.nanoTime() - started) / 1_000_000;
            System.out.println("B11b-EXPLAIN page by " + key + " desc with category and institution: " + ms + " ms, " + page.getTotal() + " match");
            assertThat(page.getTotal()).isPositive();
            assertThat(ms).isLessThan(1000);
        }
        long started = System.nanoTime();
        ParticipantQuery.PageResponse unfiltered = participants.findPage(ParticipantQuery.PageRequest.builder()
                .page(100).size(50).sort("lastName").ascending(true).build()).block();
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.println("B11b-EXPLAIN page 100 by lastName end to end (with the count): " + ms + " ms");
        assertThat(unfiltered.getTotal()).isEqualTo(100_000);
        assertThat(unfiltered.getUsers()).hasSize(50);
        assertThat(ms).isLessThan(1000);
    }
}
