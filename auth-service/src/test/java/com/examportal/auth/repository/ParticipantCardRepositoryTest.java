package com.examportal.auth.repository;

import com.examportal.common.domain.dto.internal.AdminAuditQuery;
import com.examportal.common.domain.dto.participant.ParticipantCardDtos;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B11: the account part of the participant card on a real PostgreSQL: only the shown columns (never the
 * password hash), the lock state as it is now, and the newest account events where the user acted or was
 * the target. Skipped without Docker.
 */
class ParticipantCardRepositoryTest {

    private static PostgreSQLContainer<?> postgres;
    private static DatabaseClient db;
    private static ParticipantCardRepository cards;

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
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static void audit(UUID actor, String action, UUID target, String details, int minutesAgo) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("INSERT INTO admin_audit_log (admin_user_id, action, target_user_id, details, created_at) "
                        + "VALUES (:actor, :action, :target, :details, LOCALTIMESTAMP - make_interval(mins => :ago))")
                .bind("actor", actor).bind("action", action).bind("ago", minutesAgo);
        spec = target == null ? spec.bindNull("target", UUID.class) : spec.bind("target", target);
        spec = details == null ? spec.bindNull("details", String.class) : spec.bind("details", details);
        spec.then().block();
    }

    @Test
    void theAccountWithoutSecretsAndItsEvents() {
        UUID user = db.sql("INSERT INTO users (email, password_hash, first_name, last_name, phone_number, role, email_verified, "
                        + "account_locked, locked_until) VALUES ('card@x.test', 'SECRET-HASH', 'Gülnarə', 'Məmmədova', '+994501234567', "
                        + "'STUDENT', true, true, LOCALTIMESTAMP + INTERVAL '10 minutes') RETURNING id")
                .map(row -> row.get("id", UUID.class)).one().block();
        UUID admin = UUID.randomUUID();
        audit(admin, "UNLOCK_USER", user, null, 30);
        audit(admin, "PARTICIPANT_PROFILE_UPDATED", user, "city: - -> 'Bakı'", 20);
        audit(user, "PASSWORD_CHANGED", user, null, 10);
        audit(admin, "UNLOCK_USER", UUID.randomUUID(), null, 5); // someone else's

        ParticipantCardDtos.AuthCard card = cards.findAccount(user).block();
        assertThat(card.getParticipantCode()).isBetween(1_000_000, 9_999_999);
        assertThat(card.getFirstName()).isEqualTo("Gülnarə");
        assertThat(card.getPhoneNumber()).isEqualTo("+994501234567");
        assertThat(card.getRole()).isEqualTo("STUDENT");
        assertThat(card.isActive()).isTrue();
        assertThat(card.isBlocked()).isTrue();
        assertThat(card.getLockedUntil()).isNotNull();
        assertThat(card.toString()).doesNotContain("SECRET-HASH");

        assertThat(cards.findAccountEvents(user, 20).block()).extracting(AdminAuditQuery.Entry::getAction)
                .containsExactly("PASSWORD_CHANGED", "PARTICIPANT_PROFILE_UPDATED", "UNLOCK_USER");
        assertThat(cards.findAccountEvents(user, 20).block().get(1).getDetails()).isEqualTo("city: - -> 'Bakı'");
        assertThat(cards.findAccountEvents(user, 1).block()).hasSize(1);
        assertThat(cards.findAccount(UUID.randomUUID()).blockOptional()).isEmpty();
    }
}
