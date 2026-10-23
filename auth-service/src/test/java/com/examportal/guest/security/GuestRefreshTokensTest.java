package com.examportal.guest.security;

import com.examportal.common.exception.ApplicationException;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B13a item 4 on a real PostgreSQL: a refresh token is stored only as its hash, rotates (the used one stops working),
 * a copied token presented again ends the whole sign-in, a sign-in ends after its 7 days however often it was
 * refreshed, sign-out and a block end the tokens. Skipped without Docker.
 */
class GuestRefreshTokensTest {

    private static PostgreSQLContainer<?> postgres;
    private static DatabaseClient db;
    private static GuestRefreshTokens tokens;

    @BeforeAll
    static void start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        PostgresqlConnectionFactory factory = new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
                .host(postgres.getHost()).port(postgres.getMappedPort(5432)).database(postgres.getDatabaseName())
                .username(postgres.getUsername()).password(postgres.getPassword()).build());
        db = DatabaseClient.create(factory);
        tokens = new GuestRefreshTokens(db, TransactionalOperator.create(new R2dbcTransactionManager(factory)));
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static UUID guest(String email) {
        return db.sql("INSERT INTO guest_participants (email, email_verified_at, first_name, last_name, phone, consent_at, consent_version) "
                        + "VALUES (:email, LOCALTIMESTAMP, 'A', 'B', '+994501234567', LOCALTIMESTAMP, 'v1') RETURNING id")
                .bind("email", email).map(row -> row.get(0, UUID.class)).one().block();
    }

    private static String code(Throwable error) {
        return ((ApplicationException) error).getErrorCode();
    }

    @Test
    void rotationHandsOutTheNextTokenAndTheUsedOneStops() {
        UUID id = guest("rotate@x.test");
        String first = tokens.issue(id).block();
        assertThat(db.sql("SELECT count(*) FROM guest_refresh_tokens WHERE token_hash = :raw").bind("raw", first)
                .map(row -> row.get(0, Long.class)).one().block()).isZero();

        GuestRefreshTokens.Rotation second = tokens.rotate(first).block();
        assertThat(second.guestId()).isEqualTo(id);
        assertThat(second.refreshToken()).isNotEqualTo(first);
        GuestRefreshTokens.Rotation third = tokens.rotate(second.refreshToken()).block();
        assertThat(third.guestId()).isEqualTo(id);
    }

    @Test
    void aCopiedTokenEndsTheWholeSignIn() {
        UUID id = guest("copied@x.test");
        String first = tokens.issue(id).block();
        String second = tokens.rotate(first).block().refreshToken();

        // The first one again (someone kept a copy): refused, and the one handed out after it stops too
        assertThatThrownBy(() -> tokens.rotate(first).block()).satisfies(error -> assertThat(code(error)).isEqualTo("REFRESH_TOKEN_INVALID"));
        assertThatThrownBy(() -> tokens.rotate(second).block()).satisfies(error -> assertThat(code(error)).isEqualTo("REFRESH_TOKEN_INVALID"));
    }

    @Test
    void aSignInEndsAfterItsDaysWhateverTheRotations() {
        UUID id = guest("ended@x.test");
        String first = tokens.issue(id).block();
        String second = tokens.rotate(first).block().refreshToken();
        db.sql("UPDATE guest_refresh_tokens SET family_expires_at = LOCALTIMESTAMP - interval '1 minute' WHERE guest_id = :id")
                .bind("id", id).then().block();
        assertThatThrownBy(() -> tokens.rotate(second).block()).satisfies(error -> assertThat(code(error)).isEqualTo("REFRESH_TOKEN_INVALID"));
        // The family keeps the end it was given: a rotation never moved it
        assertThat(db.sql("SELECT count(DISTINCT family_expires_at) FROM guest_refresh_tokens WHERE guest_id = :id").bind("id", id)
                .map(row -> row.get(0, Long.class)).one().block()).isEqualTo(1);
    }

    @Test
    void signOutAndBlockEndTheTokens() {
        UUID id = guest("out@x.test");
        String one = tokens.issue(id).block();
        String two = tokens.issue(id).block();
        tokens.revoke(one).block();
        assertThatThrownBy(() -> tokens.rotate(one).block()).isInstanceOf(ApplicationException.class);
        tokens.revokeAll(id).block();
        assertThatThrownBy(() -> tokens.rotate(two).block()).isInstanceOf(ApplicationException.class);
        // Unknown and empty tokens are refused the same way
        assertThatThrownBy(() -> tokens.rotate("not-a-token").block()).satisfies(error -> assertThat(code(error)).isEqualTo("REFRESH_TOKEN_INVALID"));
        assertThatThrownBy(() -> tokens.rotate("").block()).satisfies(error -> assertThat(code(error)).isEqualTo("REFRESH_TOKEN_INVALID"));
    }
}
