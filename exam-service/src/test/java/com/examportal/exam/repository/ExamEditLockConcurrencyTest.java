package com.examportal.exam.repository;

import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B9, against a real PostgreSQL (a throwaway Testcontainers database with all Flyway migrations):
 * an edit of an exam and a start of a session at the same moment never mix. Either the edit sees
 * the session and is refused, or the session is opened after the edit with the complete new set
 * of questions. Skipped when no Docker is available.
 *
 * The edit runs the statements of ExamService.updateExam in its order (lock, count sessions, replace
 * the questions); the start is ExamAttemptRepository.openSessionIfAttemptsRemain itself.
 */
class ExamEditLockConcurrencyTest {

    private static PostgreSQLContainer<?> postgres;
    private static DatabaseClient db;
    private static TransactionalOperator tx;
    private static ExamEditLock editLock;
    private static ExamAttemptRepository attempts;

    private long examId;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        PostgresqlConnectionFactory factory = new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
                .host(postgres.getHost()).port(postgres.getMappedPort(5432)).database(postgres.getDatabaseName())
                .username(postgres.getUsername()).password(postgres.getPassword()).build());
        db = DatabaseClient.create(factory);
        tx = TransactionalOperator.create(new R2dbcTransactionManager(factory));
        editLock = new ExamEditLock(db);
        attempts = new ExamAttemptRepository(db);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void anActiveExamWithThreeQuestions() {
        examId = db.sql("""
                        INSERT INTO exams (title, start_time, end_time, status, max_attempts, created_by)
                        VALUES ('T-b9 race', LOCALTIMESTAMP - interval '1 hour', LOCALTIMESTAMP + interval '1 hour',
                                'ACTIVE', 5, :owner) RETURNING id
                        """)
                .bind("owner", UUID.randomUUID())
                .map(row -> row.get("id", Long.class)).one().block();
        insertQuestions("old", 3).block();
    }

    private Mono<Void> insertQuestions(String prefix, int count) {
        Mono<Void> all = Mono.empty();
        for (int i = 0; i < count; i++) {
            int index = i;
            all = all.then(db.sql("""
                            WITH q AS (INSERT INTO questions (exam_id, question_text, question_type, points, order_index)
                                       VALUES (:examId, :text, 'MULTIPLE_CHOICE', 1, :index) RETURNING id)
                            INSERT INTO question_options (question_id, option_text, is_correct, order_index)
                            SELECT id, 'right', true, 0 FROM q UNION ALL SELECT id, 'wrong', false, 1 FROM q
                            """)
                    .bind("examId", examId).bind("text", prefix + "-" + index).bind("index", index)
                    .then());
        }
        return all;
    }

    /** The edit as ExamService.updateExam does it: lock, count sessions (refuse), replace the questions */
    private Mono<String> edit(Duration holdWhileReplacing, AtomicLong committedAt) {
        Mono<String> work = editLock.lockForEdit(examId)
                .then(editLock.hasSessions(examId))
                .flatMap(taken -> taken
                        ? Mono.just("EXAM_HAS_SESSIONS")
                        : db.sql("DELETE FROM question_options WHERE question_id IN (SELECT id FROM questions WHERE exam_id = :examId)")
                                .bind("examId", examId).then()
                                .then(db.sql("DELETE FROM questions WHERE exam_id = :examId").bind("examId", examId).then())
                                .then(Mono.delay(holdWhileReplacing))
                                .then(insertQuestions("new", 4))
                                .thenReturn("EDITED"));
        return tx.transactional(work).doOnSuccess(result -> committedAt.set(System.nanoTime()));
    }

    /** The start: open a session in its own transaction (optionally holding it open), then read the questions */
    private Mono<List<String>> start(UUID user, Duration holdBeforeCommit, AtomicLong openedAt) {
        LocalDateTime now = LocalDateTime.now();
        Mono<Long> open = attempts.openSessionIfAttemptsRemain(UUID.randomUUID().toString(), examId, user, now,
                        now.plusMinutes(30), null, null, now.minusDays(1), now.plusDays(1))
                .flatMap(rows -> Mono.delay(holdBeforeCommit).thenReturn(rows));
        return tx.transactional(open)
                .doOnSuccess(rows -> openedAt.set(System.nanoTime()))
                .flatMap(rows -> {
                    assertThat(rows).isEqualTo(1L);
                    return questions();
                });
    }

    private Mono<List<String>> questions() {
        return db.sql("SELECT question_text FROM questions WHERE exam_id = :examId ORDER BY order_index")
                .bind("examId", examId).map(row -> row.get("question_text", String.class)).all().collectList();
    }

    private Mono<Long> sessions() {
        return db.sql("SELECT count(*) AS n FROM exam_sessions WHERE exam_id = :examId").bind("examId", examId)
                .map(row -> row.get("n", Long.class)).one();
    }

    @Test
    void aStartDuringAnEditWaitsAndGetsTheCompleteNewQuestions() {
        AtomicLong editCommitted = new AtomicLong();
        AtomicLong sessionOpened = new AtomicLong();
        Mono<String> editing = edit(Duration.ofMillis(800), editCommitted).subscribeOn(Schedulers.boundedElastic());
        Mono<List<String>> starting = Mono.delay(Duration.ofMillis(250))
                .then(start(UUID.randomUUID(), Duration.ZERO, sessionOpened)).subscribeOn(Schedulers.boundedElastic());

        var both = Mono.zip(editing, starting).block(Duration.ofSeconds(30));

        assertThat(both.getT1()).isEqualTo("EDITED");
        // The session's questions are the full new set, never a mixture or an empty exam
        assertThat(both.getT2()).containsExactly("new-0", "new-1", "new-2", "new-3");
        assertThat(sessionOpened.get()).isGreaterThan(editCommitted.get());
        assertThat(sessions().block()).isEqualTo(1L);
    }

    @Test
    void anEditDuringAStartWaitsAndIsRefusedTheQuestionsStay() {
        AtomicLong editCommitted = new AtomicLong();
        AtomicLong sessionOpened = new AtomicLong();
        Mono<List<String>> starting = start(UUID.randomUUID(), Duration.ofMillis(800), sessionOpened)
                .subscribeOn(Schedulers.boundedElastic());
        Mono<String> editing = Mono.delay(Duration.ofMillis(250))
                .then(edit(Duration.ZERO, editCommitted)).subscribeOn(Schedulers.boundedElastic());

        var both = Mono.zip(editing, starting).block(Duration.ofSeconds(30));

        assertThat(both.getT1()).isEqualTo("EXAM_HAS_SESSIONS");
        assertThat(both.getT2()).containsExactly("old-0", "old-1", "old-2");
        assertThat(questions().block()).containsExactly("old-0", "old-1", "old-2");
    }

    @Test
    void manyStartsDuringAnEditAllGetTheSameCompleteSet() {
        AtomicLong editCommitted = new AtomicLong();
        Mono<String> editing = edit(Duration.ofMillis(600), editCommitted).subscribeOn(Schedulers.boundedElastic());
        List<Mono<List<String>>> starts = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> Mono.delay(Duration.ofMillis(150))
                        .then(start(UUID.randomUUID(), Duration.ZERO, new AtomicLong()))
                        .subscribeOn(Schedulers.boundedElastic()))
                .toList();

        var both = Mono.zip(editing, reactor.core.publisher.Flux.merge(starts).collectList()).block(Duration.ofSeconds(30));

        assertThat(both.getT1()).isEqualTo("EDITED");
        assertThat(both.getT2()).hasSize(8).allSatisfy(set -> assertThat(set).containsExactly("new-0", "new-1", "new-2", "new-3"));
        assertThat(sessions().block()).isEqualTo(8L);
    }

    @Test
    void beforeB9TheHardDeleteOfAnsweredQuestionsWasRefusedByTheForeignKey() {
        // What an update of an exam with answers ran into before B9 (AUDIT_GAP F5): the answers keep the questions
        LocalDateTime now = LocalDateTime.now();
        tx.transactional(attempts.openSessionIfAttemptsRemain("s-" + UUID.randomUUID(), examId, UUID.randomUUID(), now,
                now.plusMinutes(30), null, null, now.minusDays(1), now.plusDays(1))).block();
        db.sql("""
                        INSERT INTO user_answers (session_id, question_id, selected_option_ids)
                        SELECT s.id, q.id, ARRAY[]::bigint[] FROM exam_sessions s, questions q
                        WHERE s.exam_id = :examId AND q.exam_id = :examId LIMIT 1
                        """).bind("examId", examId).then().block();

        Throwable refused = db.sql("DELETE FROM questions WHERE exam_id = :examId").bind("examId", examId).then()
                .materialize().block().getThrowable();

        assertThat(refused).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(questions().block()).hasSize(3);
    }
}
