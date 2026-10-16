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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B10, auth-service V3 on a real PostgreSQL (throwaway Testcontainers database): existing accounts of
 * every role get a code (backfill, oldest first) before the column becomes NOT NULL; a new account gets
 * the next one from the default; the codes are the formula of ParticipantCode; after the last code
 * the insert fails clearly and no code is ever given twice. Skipped when no Docker is available.
 */
class ParticipantCodeMigrationTest {

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

    private static void insertUser(Connection db, String email, String role, String createdAt) throws SQLException {
        try (PreparedStatement insert = db.prepareStatement("INSERT INTO users (email, password_hash, first_name, last_name, "
                + "role, email_verified, created_at) VALUES (?, 'x', 'First', 'Last', ?, true, CAST(? AS TIMESTAMP))")) {
            insert.setString(1, email);
            insert.setString(2, role);
            insert.setString(3, createdAt);
            insert.executeUpdate();
        }
    }

    private static List<Integer> codesByAge(Connection db) throws SQLException {
        List<Integer> codes = new ArrayList<>();
        try (Statement query = db.createStatement();
             ResultSet rows = query.executeQuery("SELECT participant_code FROM users ORDER BY created_at, id")) {
            while (rows.next()) {
                codes.add(rows.getInt(1));
            }
        }
        return codes;
    }

    @Test
    void backfillThenDefaultThenTheEnd() throws SQLException {
        Flyway before = flyway("2");
        before.clean();
        before.migrate();
        try (Connection db = connect()) {
            // Accounts of every role, created before V3 (inserted out of age order on purpose)
            insertUser(db, "student-b@x.test", "STUDENT", "2026-02-01 10:00:00");
            insertUser(db, "admin@x.test", "ADMIN", "2026-01-01 10:00:00");
            insertUser(db, "instructor@x.test", "INSTRUCTOR", "2026-01-15 10:00:00");
            insertUser(db, "student-c@x.test", "STUDENT", "2026-03-01 10:00:00");
        }

        // V3 itself (V5 replaces its default by a trigger and forbids changing a code: ParticipantCodeImmutableTest)
        flyway("3").migrate();

        try (Connection db = connect()) {
            // Backfill: every account, oldest first, gets the codes of n = 0, 1, 2, 3
            assertThat(codesByAge(db)).containsExactly(ParticipantCode.forSequenceValue(0), ParticipantCode.forSequenceValue(1),
                    ParticipantCode.forSequenceValue(2), ParticipantCode.forSequenceValue(3));
            try (Statement check = db.createStatement();
                 ResultSet column = check.executeQuery("SELECT is_nullable, column_default FROM information_schema.columns "
                         + "WHERE table_name = 'users' AND column_name = 'participant_code'")) {
                assertThat(column.next()).isTrue();
                assertThat(column.getString(1)).isEqualTo("NO");
                assertThat(column.getString(2)).contains("next_participant_code");
            }

            // A new account gets the next code from the default, without the application writing it
            insertUser(db, "new@x.test", "STUDENT", "2026-10-16 10:00:00");
            assertThat(codesByAge(db)).last().isEqualTo(ParticipantCode.forSequenceValue(4));

            // The same code twice is refused by the unique constraint, a code outside 7 digits by the check
            try (Statement update = db.createStatement()) {
                int first = codesByAge(db).get(0);
                assertThatThrownBy(() -> update.executeUpdate("UPDATE users SET participant_code = " + first
                        + " WHERE email = 'new@x.test'")).hasMessageContaining("uq_users_participant_code");
                assertThatThrownBy(() -> update.executeUpdate("UPDATE users SET participant_code = 999999"
                        + " WHERE email = 'new@x.test'")).hasMessageContaining("ck_users_participant_code");
            }

            // The SQL function is the Java formula, also at the end of the range
            try (Statement jump = db.createStatement()) {
                jump.execute("SELECT setval('participant_code_seq', 8999997, true)");
            }
            insertUser(db, "last@x.test", "STUDENT", "2026-10-16 11:00:00");
            assertThat(codesByAge(db)).last().isEqualTo(ParticipantCode.forSequenceValue(8_999_998));
            insertUser(db, "very-last@x.test", "STUDENT", "2026-10-16 12:00:00");
            assertThat(codesByAge(db)).last().isEqualTo(ParticipantCode.forSequenceValue(8_999_999));

            // 9 000 000 codes are given: the next insert fails clearly, nothing is reused
            SQLException exhausted = null;
            try {
                insertUser(db, "one-too-many@x.test", "STUDENT", "2026-10-16 13:00:00");
            } catch (SQLException e) {
                exhausted = e;
            }
            assertThat((Throwable) exhausted).isNotNull();
            assertThat(exhausted.getMessage()).contains("participant_code_seq");
            assertThat(AuthService.participantCodesExhausted(new RuntimeException("insert failed", exhausted))).isTrue();
            assertThat(codesByAge(db)).doesNotHaveDuplicates().hasSize(7);
            assertThat(new HashSet<>(codesByAge(db)).size()).isEqualTo(7);
        }
    }
}
