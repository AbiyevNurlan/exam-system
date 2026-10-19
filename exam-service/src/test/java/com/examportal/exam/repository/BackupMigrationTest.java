package com.examportal.exam.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B13: the new migrations (exam V18, V19; auth V8) on a copy of a real backup. Runs only when asked for:
 * {@code -Db13.backup=F:\exam-system\backups\<stamp>} (a folder of scripts\backup.ps1 with exam_db.dump and
 * exam_auth.dump). The dumps are restored into a throwaway Testcontainers PostgreSQL, never into the running
 * database; every table keeps exactly its rows, the migrations apply, and the new columns and tables hold what they
 * should. The container and its copy are gone when the test ends.
 */
class BackupMigrationTest {

    private static final String BACKUP = System.getProperty("b13.backup");
    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void restore() throws Exception {
        assumeTrue(BACKUP != null && !BACKUP.isBlank(), "-Db13.backup=<backup folder> not given");
        Path folder = Path.of(BACKUP);
        assumeTrue(Files.isRegularFile(folder.resolve("exam_db.dump")) && Files.isRegularFile(folder.resolve("exam_auth.dump")),
                "no exam_db.dump and exam_auth.dump in " + folder);
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        for (String db : List.of("exam_db", "exam_auth")) {
            postgres.copyFileToContainer(MountableFile.forHostPath(folder.resolve(db + ".dump")), "/tmp/" + db + ".dump");
            run("createdb", "-U", postgres.getUsername(), db);
            run("pg_restore", "-U", postgres.getUsername(), "-d", db, "--no-owner", "--no-privileges", "--exit-on-error",
                    "/tmp/" + db + ".dump");
        }
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static void run(String... command) throws Exception {
        Container.ExecResult result = postgres.execInContainer(command);
        assertThat(result.getExitCode()).as(String.join(" ", command) + ": " + result.getStderr()).isZero();
    }

    private static String url(String db) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + db;
    }

    private static Connection connect(String db) throws Exception {
        return DriverManager.getConnection(url(db), postgres.getUsername(), postgres.getPassword());
    }

    /** The rows of every table of the public schema */
    private static Map<String, Long> rows(String db) throws Exception {
        Map<String, Long> rows = new TreeMap<>();
        try (Connection connection = connect(db); Statement statement = connection.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet names = statement.executeQuery(
                    "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'")) {
                while (names.next()) {
                    tables.add(names.getString(1));
                }
            }
            for (String table : tables) {
                try (ResultSet count = statement.executeQuery("SELECT count(*) FROM \"" + table + "\"")) {
                    count.next();
                    rows.put(table, count.getLong(1));
                }
            }
        }
        return rows;
    }

    private static long scalar(String db, String sql) throws Exception {
        try (Connection connection = connect(db); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static MigrateResult migrate(String db, String location) {
        return Flyway.configure().dataSource(url(db), postgres.getUsername(), postgres.getPassword())
                .locations(location).validateOnMigrate(true).baselineOnMigrate(false).cleanDisabled(true)
                .load().migrate();
    }

    @Test
    void the_b13_migrations_keep_every_row_of_the_backup() throws Exception {
        Map<String, Long> examBefore = rows("exam_db");
        Map<String, Long> authBefore = rows("exam_auth");
        System.out.println("B13-MIGRATION exam_db before: " + examBefore);
        System.out.println("B13-MIGRATION exam_auth before: " + authBefore);
        assertThat(authBefore.get("users")).as("a backup with accounts").isPositive();

        MigrateResult exam = migrate("exam_db", "classpath:db/migration");
        // Tests run in exam-service/, the migrations of auth-service are next to it
        MigrateResult auth = migrate("exam_auth", "filesystem:../auth-service/src/main/resources/db/migration");
        System.out.println("B13-MIGRATION exam_db " + exam.initialSchemaVersion + " -> " + exam.targetSchemaVersion
                + ", exam_auth " + auth.initialSchemaVersion + " -> " + auth.targetSchemaVersion);
        assertThat(exam.success).isTrue();
        assertThat(auth.success).isTrue();
        assertThat(exam.targetSchemaVersion).isEqualTo("19");
        assertThat(auth.targetSchemaVersion).isEqualTo("8");

        Map<String, Long> examAfter = rows("exam_db");
        Map<String, Long> authAfter = rows("exam_auth");
        examBefore.forEach((table, count) -> assertThat(examAfter.get(table)).as("exam_db." + table).isEqualTo(count));
        authBefore.forEach((table, count) -> assertThat(authAfter.get(table)).as("exam_auth." + table).isEqualTo(count));

        // New: no limits stored (everybody has the defaults), nobody must change a password, nobody has signed in yet
        assertThat(examAfter.get("instructor_limits")).isZero();
        assertThat(scalar("exam_auth", "SELECT count(*) FROM users WHERE must_change_password")).isZero();
        assertThat(scalar("exam_auth", "SELECT count(*) FROM users WHERE temp_password_expires_at IS NOT NULL OR last_login_at IS NOT NULL")).isZero();
        // The group names are unique per owner now, and the old global index is gone
        assertThat(scalar("exam_db", "SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_user_groups_owner_name'")).isEqualTo(1);
        assertThat(scalar("exam_db", "SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_user_groups_name'")).isZero();
    }
}
