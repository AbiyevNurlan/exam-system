package com.examportal.auth.service;

import com.examportal.common.domain.ParticipantCode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B11, auth-service V5 on a real PostgreSQL: a participant code cannot be changed by any statement, a code
 * written by an INSERT is replaced by the next one of the sequence (so the code of a deleted account is never
 * given again), existing codes stay as they were, and the end of the codes is still reported clearly.
 * Skipped when no Docker is available.
 */
class ParticipantCodeImmutableTest {

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target(target).cleanDisabled(false).load();
    }

    private static int insert(Connection db, String email, Integer code) throws SQLException {
        String sql = code == null
                ? "INSERT INTO users (email, password_hash, first_name, last_name, role) VALUES (?, 'x', 'F', 'L', 'STUDENT') RETURNING participant_code"
                : "INSERT INTO users (email, password_hash, first_name, last_name, role, participant_code) VALUES (?, 'x', 'F', 'L', 'STUDENT', ?) RETURNING participant_code";
        try (PreparedStatement insert = db.prepareStatement(sql)) {
            insert.setString(1, email);
            if (code != null) {
                insert.setInt(2, code);
            }
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private static int codeOf(Connection db, String email) throws SQLException {
        try (Statement query = db.createStatement();
             ResultSet row = query.executeQuery("SELECT participant_code FROM users WHERE email = '" + email + "'")) {
            row.next();
            return row.getInt(1);
        }
    }

    @Test
    void codesNeverChangeAndAreNeverGivenTwice() throws SQLException {
        Flyway before = flyway("4");
        before.clean();
        before.migrate();
        int existing;
        try (Connection db = connect()) {
            existing = insert(db, "before-v5@x.test", null);
        }

        flyway("latest").migrate();

        try (Connection db = connect(); Statement sql = db.createStatement()) {
            // Existing codes stay as they were
            assertThat(codeOf(db, "before-v5@x.test")).isEqualTo(existing).isEqualTo(ParticipantCode.forSequenceValue(0));

            // A new account gets the next code of the sequence, also when the statement names one
            int second = insert(db, "second@x.test", null);
            assertThat(second).isEqualTo(ParticipantCode.forSequenceValue(1));
            int forced = insert(db, "forced@x.test", existing);
            assertThat(forced).isEqualTo(ParticipantCode.forSequenceValue(2)).isNotEqualTo(existing);

            // No statement can change a code; other columns and writing the same code still work
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE users SET participant_code = 1234567 WHERE email = 'second@x.test'"))
                    .hasMessageContaining("cannot be changed");
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE users SET participant_code = NULL WHERE email = 'second@x.test'"))
                    .hasMessageContaining("cannot be changed");
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE users SET participant_code = participant_code + 1"))
                    .hasMessageContaining("cannot be changed");
            assertThat(sql.executeUpdate("UPDATE users SET first_name = 'Changed', participant_code = participant_code "
                    + "WHERE email = 'second@x.test'")).isEqualTo(1);
            assertThat(codeOf(db, "second@x.test")).isEqualTo(second);

            // After a deletion the code is not given again: neither by the next account nor when named
            sql.executeUpdate("DELETE FROM users WHERE email = 'second@x.test'");
            int afterDelete = insert(db, "after-delete@x.test", second);
            assertThat(afterDelete).isNotEqualTo(second).isEqualTo(ParticipantCode.forSequenceValue(3));

            // The end of the codes is reported as before
            sql.execute("SELECT setval('participant_code_seq', 8999999, true)");
            SQLException exhausted = null;
            try {
                insert(db, "one-too-many@x.test", null);
            } catch (SQLException e) {
                exhausted = e;
            }
            assertThat((Throwable) exhausted).isNotNull();
            assertThat(AuthService.participantCodesExhausted(new RuntimeException("insert failed", exhausted))).isTrue();

            try (ResultSet triggers = sql.executeQuery("SELECT count(*) FROM pg_trigger WHERE tgrelid = 'users'::regclass "
                    + "AND tgname IN ('trg_users_participant_code_assign', 'trg_users_participant_code_immutable')")) {
                triggers.next();
                assertThat(triggers.getInt(1)).isEqualTo(2);
            }
        }
    }
}
