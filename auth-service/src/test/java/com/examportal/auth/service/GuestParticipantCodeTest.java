package com.examportal.auth.service;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B13a, auth-service V9 on a real PostgreSQL: guests take their codes from the same sequence as accounts, so no code
 * is ever both an account's and a guest's; a code written by an INSERT is replaced, an UPDATE cannot change it; the
 * codes of existing accounts stay as they were; email in lower case, phone in E.164, a blocked guest has a reason.
 * Skipped when no Docker is available.
 */
class GuestParticipantCodeTest {

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
                .locations("classpath:db/migration").target(target == null ? "latest" : target).cleanDisabled(false).load();
    }

    private static int user(Connection db, String email) throws SQLException {
        return insert(db, "INSERT INTO users (email, password_hash, first_name, last_name, role) VALUES (?, 'x', 'F', 'L', 'STUDENT') RETURNING participant_code", email, null);
    }

    static int guest(Connection db, String email, Integer code) throws SQLException {
        String sql = code == null
                ? "INSERT INTO guest_participants (email, email_verified_at, first_name, last_name, phone, consent_at, consent_version) "
                        + "VALUES (?, LOCALTIMESTAMP, 'Aysel', 'Quliyeva', '+994501234567', LOCALTIMESTAMP, 'v1') RETURNING participant_code"
                : "INSERT INTO guest_participants (email, email_verified_at, first_name, last_name, phone, consent_at, consent_version, participant_code) "
                        + "VALUES (?, LOCALTIMESTAMP, 'Aysel', 'Quliyeva', '+994501234567', LOCALTIMESTAMP, 'v1', ?) RETURNING participant_code";
        return insert(db, sql, email, code);
    }

    private static int insert(Connection db, String sql, String email, Integer code) throws SQLException {
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

    private static Map<String, Integer> userCodes(Connection db) throws SQLException {
        Map<String, Integer> codes = new LinkedHashMap<>();
        try (Statement query = db.createStatement();
             ResultSet rows = query.executeQuery("SELECT email, participant_code FROM users ORDER BY email")) {
            while (rows.next()) {
                codes.put(rows.getString(1), rows.getInt(2));
            }
        }
        return codes;
    }

    @Test
    void guestsAndAccountsNeverShareACode() throws SQLException {
        flyway(null).clean();
        // Accounts made before V9 keep their codes when V9 comes
        flyway("8").migrate();
        Map<String, Integer> before;
        try (Connection db = connect()) {
            for (int i = 0; i < 25; i++) {
                user(db, "before" + i + "@x.test");
            }
            before = userCodes(db);
        }
        flyway(null).migrate();

        try (Connection db = connect()) {
            assertThat(userCodes(db)).isEqualTo(before);

            // Interleaved, as registrations and guests come in real life
            Set<Integer> codes = new HashSet<>(before.values());
            int made = before.size();
            for (int i = 0; i < 200; i++) {
                codes.add(i % 3 == 0 ? user(db, "user" + i + "@x.test") : guest(db, "guest" + i + "@x.test", null));
                made++;
            }
            assertThat(codes).hasSize(made).allSatisfy(code -> assertThat(code).isBetween(1_000_000, 9_999_999));

            // One query over both tables: no code twice
            try (Statement query = db.createStatement();
                 ResultSet row = query.executeQuery("SELECT count(*), count(DISTINCT code) FROM ("
                         + "SELECT participant_code AS code FROM users UNION ALL SELECT participant_code FROM guest_participants) c")) {
                row.next();
                assertThat(row.getLong(2)).isEqualTo(row.getLong(1));
            }

            // A code given by the statement is never taken: not an account's, not even a free one
            int taken = before.values().iterator().next();
            int given = guest(db, "copycat@x.test", taken);
            assertThat(given).isNotEqualTo(taken);
            assertThat(guest(db, "free@x.test", 9_999_999)).isNotEqualTo(9_999_999);
        }
    }

    @Test
    void aGuestCodeCannotBeChanged() throws SQLException {
        flyway(null).clean();
        flyway(null).migrate();
        try (Connection db = connect()) {
            int code = guest(db, "fixed@x.test", null);
            int account = user(db, "account@x.test");
            try (Statement update = db.createStatement()) {
                assertThatThrownBy(() -> update.executeUpdate("UPDATE guest_participants SET participant_code = " + account
                        + " WHERE email = 'fixed@x.test'")).hasMessageContaining("cannot be changed");
                assertThatThrownBy(() -> update.executeUpdate("UPDATE guest_participants SET participant_code = participant_code + 1"
                        + " WHERE email = 'fixed@x.test'")).hasMessageContaining("cannot be changed");
                // The same value (a row saved whole) and the other columns pass
                assertThat(update.executeUpdate("UPDATE guest_participants SET participant_code = " + code
                        + ", first_name = 'Leyla' WHERE email = 'fixed@x.test'")).isEqualTo(1);
            }
        }
    }

    @Test
    void emailPhoneAndBlockRules() throws SQLException {
        flyway(null).clean();
        flyway(null).migrate();
        try (Connection db = connect()) {
            guest(db, "one@x.test", null);
            assertThatThrownBy(() -> guest(db, "one@x.test", null)).hasMessageContaining("uq_guest_participants_email");
            assertThatThrownBy(() -> guest(db, "Upper@x.test", null)).hasMessageContaining("ck_guest_participants_email_lower");
            try (Statement update = db.createStatement()) {
                assertThatThrownBy(() -> update.executeUpdate("UPDATE guest_participants SET phone = '0501234567' WHERE email = 'one@x.test'"))
                        .hasMessageContaining("ck_guest_participants_phone");
                assertThatThrownBy(() -> update.executeUpdate("UPDATE guest_participants SET blocked = true WHERE email = 'one@x.test'"))
                        .hasMessageContaining("ck_guest_participants_blocked_reason");
                assertThat(update.executeUpdate("UPDATE guest_participants SET blocked = true, blocked_reason = 'spam' WHERE email = 'one@x.test'"))
                        .isEqualTo(1);
            }
        }
    }
}
