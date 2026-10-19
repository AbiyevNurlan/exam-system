package com.examportal.auth.repository;

import com.examportal.common.domain.dto.internal.ParticipantQuery;
import com.examportal.common.domain.dto.profile.ParticipantProfileDtos.UpdateRequest;
import com.examportal.common.exception.ValidationException;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B11b: the participant list sorted on the server (auth-service V6/V7) on a real PostgreSQL with all Flyway
 * migrations: Azerbaijani names in the order of the alphabet ignoring case and spaces, the reverse for desc,
 * pages without a repeat or a gap, equal names by participant code, a missing registration time last in both
 * directions, the whitelist, and the filters together with the order. Skipped without Docker.
 */
class ParticipantSortTest {

    // Inserted in this order, so the participant codes grow in this order
    private static final List<String> INSERTED = List.of("Zaur", "Üzeyir", "Şəkər", "Ömər", "İlqar", "Işıq", "Xanım",
            "Həsən", "Ğəni", "Əli", "Çingiz", "Bəhruz", "Aysel", "əli", "  Elçin", "Dilarə", "Cavid", "Fuad", "Gülnar",
            "Jalə", "Kamal", "Qasım", "Lalə", "Murad", "Nigar", "Orxan", "Pərviz", "Rəşad", "Səbinə", "Tural", "Ulvi",
            "Vüqar", "Yusif");

    private static final List<String> ALPHABETICAL = List.of("Aysel", "Bəhruz", "Cavid", "Çingiz", "Dilarə", "Elçin",
            "Əli", "əli", "Fuad", "Gülnar", "Ğəni", "Həsən", "Xanım", "Işıq", "İlqar", "Jalə", "Kamal", "Qasım", "Lalə",
            "Murad", "Nigar", "Orxan", "Ömər", "Pərviz", "Rəşad", "Səbinə", "Şəkər", "Tural", "Ulvi", "Üzeyir", "Vüqar",
            "Yusif", "Zaur");

    private static PostgreSQLContainer<?> postgres;
    private static DatabaseClient db;
    private static ParticipantQueryRepository participants;

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
        ParticipantProfileRepository profiles = new ParticipantProfileRepository(db);
        int n = 0;
        for (String name : INSERTED) {
            n++;
            // Last names the other way round (Z..A over the alphabet) so that lastName is not firstName again;
            // Əli and əli get the same one, so only their codes tell them apart
            int place = 0;
            while (!ALPHABETICAL.get(place).equalsIgnoreCase(name.trim())) {
                place++;
            }
            UUID id = db.sql("INSERT INTO users (email, password_hash, first_name, last_name, role, email_verified, created_at) "
                            + "VALUES (:email, 'x', :first, :last, 'STUDENT', true, :created) RETURNING id")
                    .bind("email", String.format("p%02d@x.test", n))
                    .bind("first", name)
                    .bind("last", "Soy" + String.format("%02d", 99 - place))
                    .bind("created", java.time.LocalDateTime.of(2026, 10, 1, 12, 0).plusMinutes(n))
                    .map(row -> row.get("id", UUID.class)).one().block();
            if (List.of("Şəkər", "Cavid", "İlqar", "Əli").contains(name)) {
                profiles.upsert(id, UpdateRequest.builder().category("UNIVERSITY_STUDENT").institution("ADA University").build(), id).block();
            }
        }
        // Not participants: never listed
        db.sql("INSERT INTO users (email, password_hash, first_name, last_name, role, email_verified) "
                + "VALUES ('teacher@x.test', 'x', 'Aaron', 'Aaronov', 'INSTRUCTOR', true)").then().block();
        // A registration time without a value
        db.sql("UPDATE users SET created_at = NULL WHERE first_name = 'Kamal'").then().block();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static ParticipantQuery.PageResponse page(ParticipantQuery.Filter filter, String sort, boolean ascending, int page, int size) {
        return participants.findPage(ParticipantQuery.PageRequest.builder().filter(filter).page(page).size(size)
                .sort(sort).ascending(ascending).build()).block();
    }

    private static List<String> firstNames(String sort, boolean ascending) {
        return firstNames(page(null, sort, ascending, 0, 50));
    }

    private static List<String> firstNames(ParticipantQuery.PageResponse page) {
        return page.getUsers().stream().map(user -> user.getFirstName().trim()).toList();
    }

    private static int code(String firstName) {
        return db.sql("SELECT participant_code FROM users WHERE first_name = :n").bind("n", firstName)
                .map(row -> row.get(0, Integer.class)).one().block();
    }

    @Test
    void azerbaijaniAlphabetIgnoringCaseAndSpaces() {
        assertThat(firstNames("firstName", true)).containsExactlyElementsOf(ALPHABETICAL);
        // Əli was inserted before əli: equal names ignoring case, the smaller code first
        assertThat(code("Əli")).isLessThan(code("əli"));
    }

    @Test
    void descendingIsTheExactReverse() {
        List<String> reversed = new ArrayList<>(ALPHABETICAL);
        java.util.Collections.reverse(reversed);
        assertThat(firstNames("firstName", false)).containsExactlyElementsOf(reversed);
    }

    @Test
    void theOtherKeys() {
        // Last names run Z..A over the alphabet of the first names; Əli and əli share one, so by code: Əli first
        List<String> reversed = new ArrayList<>(ALPHABETICAL);
        java.util.Collections.reverse(reversed);
        java.util.Collections.swap(reversed, reversed.indexOf("Əli"), reversed.indexOf("əli"));
        assertThat(firstNames("lastName", true)).containsExactlyElementsOf(reversed);
        assertThat(firstNames("name", true)).containsExactlyElementsOf(reversed);

        List<Integer> codes = page(null, "code", true, 0, 50).getUsers().stream().map(ParticipantQuery.Participant::getParticipantCode).toList();
        assertThat(codes).hasSize(33).isSorted();
        List<Integer> codesDown = page(null, "code", false, 0, 50).getUsers().stream().map(ParticipantQuery.Participant::getParticipantCode).toList();
        assertThat(codesDown).isEqualTo(codes.reversed());

        List<String> emails = page(null, "email", true, 0, 50).getUsers().stream().map(ParticipantQuery.Participant::getEmail).toList();
        assertThat(emails).first().isEqualTo("p01@x.test");
        assertThat(emails).isSorted();
    }

    @Test
    void aMissingRegistrationTimeComesLastInBothDirections() {
        List<String> oldest = firstNames("registeredAt", true);
        List<String> newest = firstNames("registeredAt", false);
        assertThat(oldest).first().isEqualTo("Zaur");
        assertThat(oldest).last().isEqualTo("Kamal");
        assertThat(newest).first().isEqualTo("Yusif");
        assertThat(newest).last().isEqualTo("Kamal");
        // The older default (createdAt) is the same order
        assertThat(firstNames("createdAt", false)).isEqualTo(newest);
        assertThat(firstNames(page(null, null, false, 0, 50))).isEqualTo(newest);
    }

    @Test
    void pagesWithoutARepeatOrAGap() {
        for (String sort : List.of("firstName", "lastName", "code", "email", "registeredAt")) {
            for (boolean ascending : List.of(true, false)) {
                List<UUID> paged = new ArrayList<>();
                for (int p = 0; p < 12; p++) {
                    paged.addAll(page(null, sort, ascending, p, 3).getUsers().stream().map(ParticipantQuery.Participant::getId).toList());
                }
                List<UUID> whole = page(null, sort, ascending, 0, 50).getUsers().stream().map(ParticipantQuery.Participant::getId).toList();
                assertThat(paged).as(sort + " " + ascending).hasSize(33).doesNotHaveDuplicates().isEqualTo(whole);
                assertThat(new HashSet<>(paged)).hasSize(33);
            }
        }
    }

    @Test
    void theWhitelist() {
        for (String bad : List.of("password_hash", "first_name", "lower(email)", "code; DROP TABLE users", "role")) {
            assertThatThrownBy(() -> page(null, bad, true, 0, 10)).as(bad).isInstanceOf(ValidationException.class)
                    .hasMessageContaining("sortBy");
        }
        assertThat(db.sql("SELECT count(*) FROM users").map(row -> row.get(0, Long.class)).one().block()).isEqualTo(34);
    }

    @Test
    void filtersTogetherWithTheOrder() {
        ParticipantQuery.Filter category = ParticipantQuery.Filter.builder().category("UNIVERSITY_STUDENT").institution("ada").build();
        assertThat(firstNames(page(category, "firstName", true, 0, 50))).containsExactly("Cavid", "Əli", "İlqar", "Şəkər");
        assertThat(firstNames(page(category, "firstName", false, 0, 50))).containsExactly("Şəkər", "İlqar", "Əli", "Cavid");
        assertThat(page(category, "firstName", true, 1, 3).getUsers()).extracting(ParticipantQuery.Participant::getFirstName).containsExactly("Şəkər");
        assertThat(page(category, "firstName", true, 1, 3).getTotal()).isEqualTo(4);

        // The 7-digit code search
        String code = String.valueOf(code("Işıq"));
        assertThat(firstNames(page(ParticipantQuery.Filter.builder().q(code).build(), "lastName", false, 0, 50))).containsExactly("Işıq");
        // A name search, sorted: last names Soy90..Soy99 belong to the first ten names of the alphabet
        assertThat(firstNames(page(ParticipantQuery.Filter.builder().q("soy9").build(), "firstName", true, 0, 50)))
                .isEqualTo(ALPHABETICAL.subList(0, 10));
    }
}
