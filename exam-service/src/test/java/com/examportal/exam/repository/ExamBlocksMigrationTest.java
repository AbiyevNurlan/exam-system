package com.examportal.exam.repository;

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
 * B12a, V16 on a real PostgreSQL: a database at V15 with exams (one without questions, one soft-deleted),
 * questions, options, sessions and answers is migrated. Every exam gets exactly one block "Əsas blok" with
 * free navigation, every question goes into the block of its own exam with its id and order, the exams are
 * EXAM_TIMER, and no session, answer or score changes. Afterwards a new exam gets its block from the
 * trigger and a question inserted without a block lands in it. Skipped without Docker.
 */
class ExamBlocksMigrationTest {

    private static final String OPTIONS_FINGERPRINT = "SELECT md5(string_agg(id || ':' || question_id || ':' || option_text || ':' "
            + "|| COALESCE(is_correct::text, '') || ':' || order_index, ',' ORDER BY id)) FROM question_options";

    private static PostgreSQLContainer<?> postgres;
    private static DatabaseClient db;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target("15").load().migrate();
        db = DatabaseClient.create(new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
                .host(postgres.getHost()).port(postgres.getMappedPort(5432)).database(postgres.getDatabaseName())
                .username(postgres.getUsername()).password(postgres.getPassword()).build()));
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static long scalar(String sql) {
        return db.sql(sql).map(row -> ((Number) row.get(0)).longValue()).one().block();
    }

    private static String text(String sql) {
        return db.sql(sql).map(row -> row.get(0, String.class)).one().block();
    }

    private static long exam(String title, boolean shuffle, boolean active) {
        return db.sql("INSERT INTO exams (title, start_time, end_time, status, created_by, shuffle_questions, active) "
                        + "VALUES (:t, LOCALTIMESTAMP, LOCALTIMESTAMP + interval '1 day', 'ACTIVE', :o, :s, :a) RETURNING id")
                .bind("t", title).bind("o", UUID.randomUUID()).bind("s", shuffle).bind("a", active)
                .map(row -> row.get(0, Long.class)).one().block();
    }

    private static long question(long examId, int index) {
        long id = db.sql("INSERT INTO questions (exam_id, question_text, question_type, points, order_index) "
                        + "VALUES (:e, 'q' || :i, 'MULTIPLE_CHOICE', 2, :i) RETURNING id")
                .bind("e", examId).bind("i", index).map(row -> row.get(0, Long.class)).one().block();
        db.sql("INSERT INTO question_options (question_id, option_text, is_correct, order_index) VALUES "
                + "(:q, 'right', true, 0), (:q, 'wrong', false, 1)").bind("q", id).then().block();
        return id;
    }

    @Test
    void everyExamGetsOneBlockAndNothingElseChanges() {
        long first = exam("T-mig first", true, true);
        long second = exam("T-mig second", false, true);
        long empty = exam("T-mig empty", false, true);
        long deleted = exam("T-mig deleted", false, false);
        for (int i = 0; i < 3; i++) {
            question(first, i);
            question(second, i);
        }
        question(deleted, 0);
        // A completed session with answers and a score
        db.sql("INSERT INTO exam_sessions (session_id, exam_id, user_id, status, score, total_points, percentage, passed) "
                + "VALUES ('mig-1', :e, :u, 'COMPLETED', 4, 6, 66.67, true)").bind("e", first).bind("u", UUID.randomUUID()).then().block();
        db.sql("INSERT INTO user_answers (session_id, question_id, selected_option_ids, is_correct, points_earned) "
                + "SELECT s.id, q.id, ARRAY[(SELECT min(o.id) FROM question_options o WHERE o.question_id = q.id)], true, 2 "
                + "FROM exam_sessions s JOIN questions q ON q.exam_id = s.exam_id WHERE s.session_id = 'mig-1'").then().block();

        String questionsBefore = text("SELECT md5(string_agg(id || ':' || exam_id || ':' || order_index || ':' || points, ',' ORDER BY id)) FROM questions");
        String sessionsBefore = text("SELECT md5(string_agg(s::text, ',' ORDER BY id)) FROM exam_sessions s");
        String answersBefore = text("SELECT md5(string_agg(a::text, ',' ORDER BY id)) FROM user_answers a");
        // The columns of V15 only: later migrations add columns (V21 match_text), which change o::text but no value
        String optionsBefore = text(OPTIONS_FINGERPRINT);

        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();

        long exams = scalar("SELECT count(*) FROM exams");
        assertThat(scalar("SELECT count(*) FROM exam_blocks")).isEqualTo(exams).isEqualTo(4);
        assertThat(scalar("SELECT count(DISTINCT exam_id) FROM exam_blocks")).isEqualTo(4);
        assertThat(scalar("SELECT count(*) FROM exam_blocks WHERE position = 1 AND title = 'Əsas blok' AND free_navigation "
                + "AND time_limit_minutes IS NULL AND break_after_minutes = 0 AND show_result_to_participant")).isEqualTo(4);
        assertThat(scalar("SELECT count(*) FROM exam_blocks b JOIN exams e ON e.id = b.exam_id "
                + "WHERE b.shuffle_questions = COALESCE(e.shuffle_questions, false)")).isEqualTo(4);
        assertThat(scalar("SELECT count(*) FROM exam_blocks WHERE exam_id = " + empty)).isEqualTo(1);
        // Every question in the block of its own exam
        assertThat(scalar("SELECT count(*) FROM questions q JOIN exam_blocks b ON b.id = q.block_id AND b.exam_id = q.exam_id"))
                .isEqualTo(scalar("SELECT count(*) FROM questions")).isEqualTo(7);
        assertThat(scalar("SELECT count(*) FROM exams WHERE timing_mode = 'EXAM_TIMER' AND NOT allow_return_to_previous_blocks "
                + "AND allow_skip_break AND show_block_breakdown")).isEqualTo(4);
        // Nothing of the questions, options, sessions and answers changed
        assertThat(text("SELECT md5(string_agg(id || ':' || exam_id || ':' || order_index || ':' || points, ',' ORDER BY id)) FROM questions"))
                .isEqualTo(questionsBefore);
        assertThat(text("SELECT md5(string_agg(s::text, ',' ORDER BY id)) FROM exam_sessions s")).isEqualTo(sessionsBefore);
        assertThat(text("SELECT md5(string_agg(a::text, ',' ORDER BY id)) FROM user_answers a")).isEqualTo(answersBefore);
        assertThat(text(OPTIONS_FINGERPRINT)).isEqualTo(optionsBefore);
        assertThat(text("SELECT is_nullable FROM information_schema.columns WHERE table_name = 'questions' AND column_name = 'block_id'"))
                .isEqualTo("NO");

        // After V16: a new exam has its block at once, a question without a block goes into it
        long fresh = exam("T-mig fresh", false, true);
        long block = scalar("SELECT id FROM exam_blocks WHERE exam_id = " + fresh);
        long q = question(fresh, 0);
        assertThat(scalar("SELECT block_id FROM questions WHERE id = " + q)).isEqualTo(block);
        // A block of another exam is refused
        long foreignBlock = scalar("SELECT id FROM exam_blocks WHERE exam_id = " + first);
        assertThatThrownBy(() -> db.sql("INSERT INTO questions (exam_id, block_id, question_text, question_type, points, order_index) "
                + "VALUES (" + fresh + ", " + foreignBlock + ", 'x', 'MULTIPLE_CHOICE', 1, 1)").then().block())
                .hasMessageContaining("fk_questions_block");
        // A second block at the same position is refused at commit
        assertThatThrownBy(() -> db.sql("INSERT INTO exam_blocks (exam_id, position, title) VALUES (" + fresh + ", 1, 'dup')").then().block())
                .hasMessageContaining("uq_exam_blocks_exam_position");
        // Deleting an exam takes its blocks and questions with it
        db.sql("DELETE FROM exams WHERE id = " + fresh).then().block();
        assertThat(scalar("SELECT count(*) FROM exam_blocks WHERE exam_id = " + fresh)).isZero();
    }
}
