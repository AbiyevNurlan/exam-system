package com.examportal.auth.repository;

import com.examportal.common.domain.dto.profile.ParticipantProfileDtos;
import com.examportal.common.domain.dto.profile.ParticipantProfileDtos.UpdateRequest;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B11, auth-service V4 on a real PostgreSQL (throwaway Testcontainers database): accounts from before
 * V4 keep working without a profile; one profile per user, replaced by an upsert; categories are a lookup
 * (an unknown one is refused by the foreign key, a new one is one INSERT); the profile goes with its user.
 * Skipped when no Docker is available.
 */
class ParticipantProfileRepositoryTest {

    private static PostgreSQLContainer<?> postgres;
    private static DatabaseClient db;
    private static ParticipantProfileRepository repository;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        db = DatabaseClient.create(new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
                .host(postgres.getHost()).port(postgres.getMappedPort(5432)).database(postgres.getDatabaseName())
                .username(postgres.getUsername()).password(postgres.getPassword()).build()));
        repository = new ParticipantProfileRepository(db);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target(target).cleanDisabled(false).load();
    }

    private static UUID user(String email) {
        return db.sql("INSERT INTO users (email, password_hash, first_name, last_name, role, email_verified) "
                        + "VALUES (:email, 'x', 'F', 'L', 'STUDENT', true) RETURNING id")
                .bind("email", email)
                .map(row -> row.get("id", UUID.class)).one().block();
    }

    @Test
    void profilesOnTopOfExistingAccounts() {
        Flyway before = flyway("3");
        before.clean();
        before.migrate();
        UUID legacy = user("legacy@x.test");

        flyway("latest").migrate();

        // An account from before V4: no profile, and that is not an error
        assertThat(repository.findByUserId(legacy).blockOptional()).isEmpty();
        assertThat(repository.activeCategories().block())
                .containsExactly("STUDENT", "UNIVERSITY_STUDENT", "SCHOOL_STUDENT", "EMPLOYEE", "INTERN", "OTHER");

        // First save by the participant, then a change by an administrator: still one row
        UUID admin = UUID.randomUUID();
        repository.upsert(legacy, UpdateRequest.builder().category("UNIVERSITY_STUDENT").institution("ADA")
                .birthDate(LocalDate.of(2004, 5, 6)).build(), legacy).block();
        ParticipantProfileDtos.Profile own = repository.findByUserId(legacy).block();
        assertThat(own.getCategory()).isEqualTo("UNIVERSITY_STUDENT");
        assertThat(own.getInstitution()).isEqualTo("ADA");
        assertThat(own.getBirthDate()).isEqualTo(LocalDate.of(2004, 5, 6));
        assertThat(own.isUpdatedByAdmin()).isFalse();

        repository.upsert(legacy, UpdateRequest.builder().category("INTERN").workplace("Azercell").build(), admin).block();
        ParticipantProfileDtos.Profile changed = repository.findByUserId(legacy).block();
        assertThat(changed.getCategory()).isEqualTo("INTERN");
        assertThat(changed.getInstitution()).isNull();
        assertThat(changed.getWorkplace()).isEqualTo("Azercell");
        assertThat(changed.isUpdatedByAdmin()).isTrue();
        assertThat(db.sql("SELECT COUNT(*) AS n, MAX(version) AS v FROM participant_profiles WHERE user_id = :u").bind("u", legacy)
                .map(row -> row.get("n", Long.class) + "/" + row.get("v", Long.class)).one().block()).isEqualTo("1/1");

        // An unknown category is refused by the lookup; a new kind is one row in participant_categories
        UUID other = user("other@x.test");
        assertThatThrownBy(() -> repository.upsert(other, UpdateRequest.builder().category("VOLUNTEER").build(), other).block())
                .hasMessageContaining("participant_profiles_category_fkey");
        db.sql("INSERT INTO participant_categories (code, sort_order) VALUES ('VOLUNTEER', 60)").then().block();
        repository.upsert(other, UpdateRequest.builder().category("VOLUNTEER").build(), other).block();
        assertThat(repository.findByUserId(other).block().getCategory()).isEqualTo("VOLUNTEER");
        // An inactive kind stays valid for who has it but is not offered any more
        db.sql("UPDATE participant_categories SET active = false WHERE code = 'VOLUNTEER'").then().block();
        assertThat(repository.activeCategories().block()).doesNotContain("VOLUNTEER");
        assertThat(repository.findByUserId(other).block().getCategory()).isEqualTo("VOLUNTEER");

        // The profile goes with its user
        db.sql("DELETE FROM users WHERE id = :u").bind("u", other).then().block();
        assertThat(db.sql("SELECT COUNT(*) FROM participant_profiles WHERE user_id = :u").bind("u", other)
                .map(row -> row.get(0, Long.class)).one().block()).isZero();

        // Lengths are those of the API limits; the audit log can carry details now
        assertThat(db.sql("SELECT character_maximum_length FROM information_schema.columns WHERE table_name = 'participant_profiles' "
                        + "AND column_name = 'course_or_grade'").map(row -> row.get(0, Integer.class)).one().block())
                .isEqualTo(ParticipantProfileDtos.MAX_COURSE_OR_GRADE);
        assertThat(db.sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'admin_audit_log' AND column_name = 'details'")
                .map(row -> row.get(0, Long.class)).one().block()).isEqualTo(1L);
    }
}
