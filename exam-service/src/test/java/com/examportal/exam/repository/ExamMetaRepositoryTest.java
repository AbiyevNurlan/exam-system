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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B10, on a real PostgreSQL (throwaway Testcontainers database with all migrations): the meta change
 * writes title and description only; questions, options, points, the answer key, the duration, the
 * window, the attempts and every stored result stay exactly as they were. Skipped without Docker.
 */
class ExamMetaRepositoryTest {

    private static PostgreSQLContainer<?> postgres;
    private static DatabaseClient db;

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
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /** Everything about the exam that decides a result, as one text */
    private static String resultShape(long examId) {
        return db.sql("""
                        SELECT e.duration_minutes || '|' || e.start_time || '|' || e.end_time || '|' || e.max_attempts || '|'
                               || e.passing_score || '|' || e.status || '|'
                               || (SELECT string_agg(q.question_text || ':' || q.points || ':' || q.order_index || ':'
                                      || (SELECT string_agg(o.option_text || '=' || o.is_correct, ',' ORDER BY o.order_index)
                                          FROM question_options o WHERE o.question_id = q.id), ';' ORDER BY q.order_index)
                                   FROM questions q WHERE q.exam_id = e.id) || '|'
                               || (SELECT string_agg(s.percentage || ':' || s.passed || ':' || s.score, ',' ORDER BY s.id)
                                   FROM exam_sessions s WHERE s.exam_id = e.id) AS shape
                        FROM exams e WHERE e.id = :examId
                        """)
                .bind("examId", examId).map(row -> row.get("shape", String.class)).one().block();
    }

    private static String titleDescriptionVersion(long examId) {
        return db.sql("SELECT title || '|' || COALESCE(description, '<none>') || '|' || version AS t FROM exams WHERE id = :examId")
                .bind("examId", examId).map(row -> row.get("t", String.class)).one().block();
    }

    @Test
    void onlyTitleAndDescriptionChange() {
        long examId = db.sql("""
                        INSERT INTO exams (title, description, start_time, end_time, status, created_by, duration_minutes, version)
                        VALUES ('T-b10 meta', 'old text', LOCALTIMESTAMP - interval '1 hour', LOCALTIMESTAMP + interval '1 hour',
                                'ACTIVE', :owner, 45, 3) RETURNING id
                        """).bind("owner", UUID.randomUUID()).map(row -> row.get("id", Long.class)).one().block();
        db.sql("""
                WITH q AS (INSERT INTO questions (exam_id, question_text, question_type, points, order_index)
                           VALUES (:examId, 'q1', 'MULTIPLE_CHOICE', 2, 0) RETURNING id)
                INSERT INTO question_options (question_id, option_text, is_correct, order_index)
                SELECT id, 'right', true, 0 FROM q UNION ALL SELECT id, 'wrong', false, 1 FROM q
                """).bind("examId", examId).then().block();
        db.sql("""
                INSERT INTO exam_sessions (session_id, exam_id, user_id, status, score, total_points, percentage, passed, submitted_at)
                VALUES (:sid, :examId, :user, 'COMPLETED', 2, 2, 100, true, LOCALTIMESTAMP)
                """).bind("sid", UUID.randomUUID().toString()).bind("examId", examId).bind("user", UUID.randomUUID()).then().block();
        String before = resultShape(examId);
        ExamMetaRepository meta = new ExamMetaRepository(db);

        assertThat(meta.update(examId, true, "T-b10 meta renamed", false, null).block()).isEqualTo(1L);
        assertThat(titleDescriptionVersion(examId)).isEqualTo("T-b10 meta renamed|old text|4");

        assertThat(meta.update(examId, false, null, true, null).block()).isEqualTo(1L);
        assertThat(titleDescriptionVersion(examId)).isEqualTo("T-b10 meta renamed|<none>|5");

        assertThat(resultShape(examId)).isEqualTo(before);
        assertThat(meta.update(999_999L, true, "Nobody", false, null).block()).isZero();
    }
}
