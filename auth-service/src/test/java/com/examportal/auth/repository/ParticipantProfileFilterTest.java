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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B11: the participant list filtered by profile category and the start of the institution (ignoring case,
 * % and _ as typed), alone and together and with the other filters; accounts without a profile never match;
 * the 7-digit code search of B10 still finds its account. Skipped without Docker.
 */
class ParticipantProfileFilterTest {

    private static PostgreSQLContainer<?> postgres;
    private static DatabaseClient db;
    private static ParticipantQueryRepository participants;
    private static ParticipantProfileRepository profiles;

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
        profiles = new ParticipantProfileRepository(db);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static UUID user(String email, String category, String institution) {
        UUID id = db.sql("INSERT INTO users (email, password_hash, first_name, last_name, role, email_verified) "
                        + "VALUES (:email, 'x', 'F', 'L', 'STUDENT', true) RETURNING id")
                .bind("email", email).map(row -> row.get("id", UUID.class)).one().block();
        if (category != null) {
            profiles.upsert(id, UpdateRequest.builder().category(category).institution(institution).build(), id).block();
        }
        return id;
    }

    private static java.util.List<String> emails(ParticipantQuery.Filter filter) {
        return participants.findPage(ParticipantQuery.PageRequest.builder().filter(filter).page(0).size(50).sort("email").ascending(true).build())
                .block().getUsers().stream().map(ParticipantQuery.Participant::getEmail).toList();
    }

    @Test
    void byCategoryAndInstitution() {
        user("ada@x.test", "UNIVERSITY_STUDENT", "ADA University");
        user("ada2@x.test", "UNIVERSITY_STUDENT", "ada universiteti");
        user("bdu@x.test", "UNIVERSITY_STUDENT", "BDU");
        user("intern@x.test", "INTERN", "Azercell");
        user("percent@x.test", "OTHER", "100% Liseyi");
        user("none@x.test", null, null);

        assertThat(emails(ParticipantQuery.Filter.builder().category("UNIVERSITY_STUDENT").build()))
                .containsExactly("ada2@x.test", "ada@x.test", "bdu@x.test");
        assertThat(emails(ParticipantQuery.Filter.builder().category("university_student").build())).hasSize(3);
        assertThat(emails(ParticipantQuery.Filter.builder().institution("ada").build())).containsExactly("ada2@x.test", "ada@x.test");
        assertThat(emails(ParticipantQuery.Filter.builder().institution("  AdA uni ").build())).containsExactly("ada2@x.test", "ada@x.test");
        // The start of the name, not any part of it
        assertThat(emails(ParticipantQuery.Filter.builder().institution("University").build())).isEmpty();
        // % is a character, not a wildcard
        assertThat(emails(ParticipantQuery.Filter.builder().institution("100%").build())).containsExactly("percent@x.test");
        assertThat(emails(ParticipantQuery.Filter.builder().institution("1_0").build())).isEmpty();
        // Together, and with another filter
        assertThat(emails(ParticipantQuery.Filter.builder().category("INTERN").institution("ada").build())).isEmpty();
        assertThat(emails(ParticipantQuery.Filter.builder().category("INTERN").institution("azer").build())).containsExactly("intern@x.test");
        assertThat(emails(ParticipantQuery.Filter.builder().category("UNIVERSITY_STUDENT").q("bdu").build())).containsExactly("bdu@x.test");
        // Without a profile filter everybody is listed, also the account without a profile
        assertThat(emails(ParticipantQuery.Filter.builder().build())).contains("none@x.test").hasSize(6);
        // The ids for a bulk addition follow the same filter
        assertThat(participants.findIds(ParticipantQuery.IdsRequest.builder()
                .filter(ParticipantQuery.Filter.builder().category("UNIVERSITY_STUDENT").build()).limit(100).build()).block().getTotal())
                .isEqualTo(3);

        // The code search of B10 is unchanged
        Integer code = db.sql("SELECT participant_code FROM users WHERE email = 'bdu@x.test'").map(row -> row.get(0, Integer.class)).one().block();
        assertThat(emails(ParticipantQuery.Filter.builder().q(String.valueOf(code)).build())).containsExactly("bdu@x.test");

        assertThatThrownBy(() -> emails(ParticipantQuery.Filter.builder().category("not a code!").build())).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> emails(ParticipantQuery.Filter.builder().institution("a").build())).isInstanceOf(ValidationException.class);
    }
}
