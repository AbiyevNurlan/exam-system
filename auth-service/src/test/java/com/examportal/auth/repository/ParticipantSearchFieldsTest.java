package com.examportal.auth.repository;

import com.examportal.common.domain.dto.internal.ParticipantQuery;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The separate searches of the participant list on a real PostgreSQL: the name box (first name, last name or
 * "first last"), the email box and the code box each look at their own field only, the start of the value, ignoring
 * case, and are combined with AND. A participant code is found by its first 2 to 7 digits. Skipped without Docker.
 */
class ParticipantSearchFieldsTest {

    private static PostgreSQLContainer<?> postgres;
    private static DatabaseClient db;
    private static ParticipantQueryRepository participants;
    private static int codeOfNigar;

    @BeforeAll
    static void start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        db = DatabaseClient.create(new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
                .host(postgres.getHost()).port(postgres.getMappedPort(5432)).database(postgres.getDatabaseName())
                .username(postgres.getUsername()).password(postgres.getPassword()).build()));
        participants = new ParticipantQueryRepository(db);
        user("nigar@x.test", "Nigar", "Əliyeva");
        user("ali@x.test", "Əli", "Nigarov");
        user("murad@nigar.test", "Murad", "Quliyev");
        user("100%@x.test", "Fuad", "Hüseynov");
        codeOfNigar = db.sql("SELECT participant_code FROM users WHERE email = 'nigar@x.test'")
                .map(row -> row.get(0, Integer.class)).one().block();
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static void user(String email, String first, String last) {
        db.sql("INSERT INTO users (email, password_hash, first_name, last_name, role, email_verified) "
                        + "VALUES (:e, 'x', :f, :l, 'STUDENT', true)")
                .bind("e", email).bind("f", first).bind("l", last).then().block();
    }

    private static List<String> emails(ParticipantQuery.Filter filter) {
        return participants.findPage(ParticipantQuery.PageRequest.builder().filter(filter).page(0).size(50).sort("email").ascending(true).build())
                .block().getUsers().stream().map(ParticipantQuery.Participant::getEmail).toList();
    }

    @Test
    void theNameBoxLooksAtNamesOnly() {
        // First name, last name and "first last" of the start; the email "murad@nigar.test" has Nigar in it but is no name
        assertThat(emails(ParticipantQuery.Filter.builder().name("nig").build())).containsExactly("ali@x.test", "nigar@x.test");
        assertThat(emails(ParticipantQuery.Filter.builder().name("NIGAR Əl").build())).containsExactly("nigar@x.test");
        // q, the older single box, still reads the email too
        assertThat(emails(ParticipantQuery.Filter.builder().q("nigar").build())).containsExactly("ali@x.test", "nigar@x.test");
        assertThat(emails(ParticipantQuery.Filter.builder().name("x.t").build())).isEmpty();
    }

    @Test
    void theEmailBoxLooksAtTheEmailOnly() {
        assertThat(emails(ParticipantQuery.Filter.builder().email("nigar").build())).containsExactly("nigar@x.test");
        assertThat(emails(ParticipantQuery.Filter.builder().email("MURAD@").build())).containsExactly("murad@nigar.test");
        // % is a character, not a wildcard
        assertThat(emails(ParticipantQuery.Filter.builder().email("100%").build())).containsExactly("100%@x.test");
        assertThat(emails(ParticipantQuery.Filter.builder().email("1_0").build())).isEmpty();
        // The name is not in it
        assertThat(emails(ParticipantQuery.Filter.builder().email("quliyev").build())).isEmpty();
    }

    @Test
    void theCodeBoxFindsTheStartOfTheCode() {
        String code = String.valueOf(codeOfNigar);
        assertThat(emails(ParticipantQuery.Filter.builder().code(code).build())).containsExactly("nigar@x.test");
        assertThat(emails(ParticipantQuery.Filter.builder().code(code.substring(0, 3)).build())).contains("nigar@x.test");
        assertThat(emails(ParticipantQuery.Filter.builder().code(code.substring(0, 2)).build())).contains("nigar@x.test");
        // A code that starts differently does not match
        String other = (code.charAt(0) == '9' ? "1" : "9") + code.substring(1);
        assertThat(emails(ParticipantQuery.Filter.builder().code(other).build())).doesNotContain("nigar@x.test");
        assertThatThrownBy(() -> emails(ParticipantQuery.Filter.builder().code("1").build())).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> emails(ParticipantQuery.Filter.builder().code("12a").build())).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> emails(ParticipantQuery.Filter.builder().code("12345678").build())).isInstanceOf(ValidationException.class);
    }

    @Test
    void theBoxesAreCombinedWithAnd() {
        assertThat(emails(ParticipantQuery.Filter.builder().name("nig").email("ali").build())).containsExactly("ali@x.test");
        assertThat(emails(ParticipantQuery.Filter.builder().name("nig").email("murad").build())).isEmpty();
        assertThat(emails(ParticipantQuery.Filter.builder().name("nigar").code(String.valueOf(codeOfNigar)).build())).containsExactly("nigar@x.test");
        assertThatThrownBy(() -> emails(ParticipantQuery.Filter.builder().name("n").build())).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> emails(ParticipantQuery.Filter.builder().email("a").build())).isInstanceOf(ValidationException.class);
    }
}
