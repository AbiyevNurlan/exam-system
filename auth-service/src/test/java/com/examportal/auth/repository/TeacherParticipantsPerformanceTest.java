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

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B13: the auth-service half of a teacher's participant list. exam-service hands over the ids of the teacher's
 * 20 000 participants (includeIds); among 100 000 participants a page by name, a deep page, a search by name and
 * by a 7-digit code each finish within 300 ms over R2DBC (median of five runs after a warm-up), with the count.
 * Times are printed (B13-EXPLAIN). Skipped without Docker.
 */
class TeacherParticipantsPerformanceTest {

    private static final long LIMIT_MS = 300;

    private static PostgreSQLContainer<?> postgres;
    private static DatabaseClient db;
    private static ParticipantQueryRepository participants;
    private static List<UUID> own;
    private static int someCode;

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
        participants = new ParticipantQueryRepository(db);
        String firstNames = "(ARRAY['Əli','Çingiz','Ğəni','Həsən','Xanım','Işıq','İlqar','Ömər','Şəkər','Üzeyir','Aydın','Leyla','Nərmin','Kamran','Qasım','Zaur'])";
        String lastNames = "(ARRAY['Əliyev','Məmmədova','Hüseynov','Quliyev','Şirinova','İsmayılov','Çələbi','Ağayev','Babayeva','Zeynalov'])";
        for (String statement : List.of(
                "INSERT INTO users (email, password_hash, first_name, last_name, role, email_verified, created_at) "
                        + "SELECT 'tp' || g || '@x.test', 'x', " + firstNames + "[1 + g % 16], " + lastNames + "[1 + (g / 16) % 10], "
                        + "'STUDENT', true, now() - g * interval '1 minute' FROM generate_series(1, 100000) g",
                "ANALYZE")) {
            db.sql(statement).then().block();
        }
        // Every fifth participant is the teacher's, spread over the whole table
        own = db.sql("SELECT id FROM users WHERE email LIKE 'tp%@x.test' AND split_part(substring(email from 3), '@', 1)::int % 5 = 0 ORDER BY id")
                .map(row -> row.get("id", UUID.class)).all().collectList().block();
        someCode = db.sql("SELECT participant_code FROM users WHERE id = :id").bind("id", own.get(777))
                .map(row -> row.get(0, Integer.class)).one().block();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static <T> T timed(String name, Supplier<T> call) {
        T result = call.get();
        long[] runs = new long[5];
        for (int i = 0; i < runs.length; i++) {
            long started = System.nanoTime();
            result = call.get();
            runs[i] = (System.nanoTime() - started) / 1_000_000;
        }
        Arrays.sort(runs);
        System.out.println("B13-EXPLAIN " + name + ": median " + runs[2] + " ms, max " + runs[4] + " ms");
        assertThat(runs[2]).as(name).isLessThan(LIMIT_MS);
        return result;
    }

    private static ParticipantQuery.PageResponse page(String q, int page, String sort) {
        return participants.findPage(ParticipantQuery.PageRequest.builder()
                .filter(ParticipantQuery.Filter.builder().includeIds(own).q(q).build())
                .page(page).size(25).sort(sort).ascending(true).build()).block();
    }

    @Test
    void a_page_of_the_own_20000_participants_within_300_ms() {
        assertThat(own).hasSize(20_000);

        ParticipantQuery.PageResponse first = timed("first page by last name", () -> page(null, 0, "lastName"));
        assertThat(first.getTotal()).isEqualTo(20_000);
        assertThat(first.getUsers()).hasSize(25).allMatch(user -> own.contains(user.getId()));

        assertThat(timed("page 400 by first name", () -> page(null, 400, "firstName")).getUsers()).hasSize(25);
        assertThat(timed("newest first", () -> page(null, 0, "registeredAt")).getUsers()).hasSize(25);

        ParticipantQuery.PageResponse byName = timed("search by name", () -> page("Leyla", 0, "lastName"));
        assertThat(byName.getTotal()).isPositive().isLessThan(20_000);
        assertThat(byName.getUsers()).allMatch(user -> own.contains(user.getId()));

        ParticipantQuery.PageResponse byCode = timed("search by code", () -> page(String.valueOf(someCode), 0, "lastName"));
        assertThat(byCode.getUsers()).extracting(ParticipantQuery.Participant::getId).containsExactly(own.get(777));
    }
}
