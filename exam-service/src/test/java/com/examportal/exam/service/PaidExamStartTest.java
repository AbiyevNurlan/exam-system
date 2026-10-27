package com.examportal.exam.service;

import com.examportal.common.domain.dto.StartExamRequest;
import com.examportal.common.exception.ApplicationException;
import com.examportal.paid.PaidErrors;
import com.examportal.paid.order.EntitlementRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B13b item 9 with the real ExamService on PostgreSQL: a guest with an ACTIVE entitlement starts a PAID_OPEN exam and
 * one attempt is taken; an open session is answered again without another; two starts at once give one session and
 * take one attempt; without an attempt left (or without an entitlement) 403 NOT_ENTITLED and no session; an exam that
 * is not PAID_OPEN is 404 for a guest; and a registered participant still starts an AUDIENCE exam as before.
 * Skipped without Docker.
 */
class PaidExamStartTest {

    private static ExamTestStack stack;
    private static ExamService service;
    private static EntitlementRepository entitlements;

    @BeforeAll
    static void start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        stack = ExamTestStack.start();
        service = stack.examService(new ExamFeatures(false, false));
        entitlements = new EntitlementRepository(stack.db);
        // The answers of a new session: none (the stack mocks the answer service)
        AnswerSubmissionService answers = (AnswerSubmissionService) org.springframework.test.util.ReflectionTestUtils.getField(service, "answerService");
        org.mockito.Mockito.when(answers.savedAnswers(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.just(List.of()));
    }

    @AfterAll
    static void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    /** An ACTIVE exam whose window is open now; PAID_OPEN when paid */
    private static long exam(boolean paid) {
        LocalDateTime now = LocalDateTime.now();
        long id = stack.db.sql("INSERT INTO exams (title, description, duration_minutes, start_time, end_time, status, total_questions, "
                        + "passing_score, max_attempts, created_by, access_type, active, created_at, updated_at, version) "
                        + "VALUES ('T-b13b start', 'x', 30, :start, :end, 'ACTIVE', 0, 50, 1, :owner, 'PUBLIC', true, :now, :now, 0) RETURNING id")
                .bind("start", now.minusMinutes(5)).bind("end", now.plusHours(2)).bind("owner", UUID.randomUUID()).bind("now", now)
                .map(row -> row.get(0, Long.class)).one().block();
        // The block every new exam gets, one question with two options: an exam as it is published
        long block = stack.db.sql("SELECT id FROM exam_blocks WHERE exam_id = :exam ORDER BY position LIMIT 1")
                .bind("exam", id).map(row -> row.get(0, Long.class)).one().block();
        long question = stack.db.sql("INSERT INTO questions (exam_id, block_id, question_text, question_type, points, order_index) "
                        + "VALUES (:exam, :block, '1 + 1 = ?', 'MULTIPLE_CHOICE', 1, 0) RETURNING id")
                .bind("exam", id).bind("block", block).map(row -> row.get(0, Long.class)).one().block();
        stack.db.sql("INSERT INTO question_options (question_id, option_text, is_correct, order_index) VALUES (" + question + ", '2', true, 0), ("
                + question + ", '3', false, 1)").then().block();
        stack.db.sql("UPDATE exams SET total_questions = 1 WHERE id = " + id).then().block();
        if (paid) {
            stack.db.sql("UPDATE exams SET access_mode = 'PAID_OPEN', price_minor = 1000, sales_open_at = :open, sales_close_at = :close WHERE id = :id")
                    .bind("open", now.minusDays(2)).bind("close", now.minusHours(1)).bind("id", id).then().block();
        }
        return id;
    }

    private static void entitle(UUID guest, long exam, int attempts) {
        stack.db.sql("INSERT INTO entitlements (guest_id, exam_id, status, attempts_left, granted_by, grant_reason) VALUES ('" + guest + "', "
                + exam + ", 'ACTIVE', " + attempts + ", '" + UUID.randomUUID() + "', 'test')").then().block();
    }

    private static Mono<String> startAs(UUID guest, long exam) {
        return service.startPaidExam(exam, guest, new StartExamRequest(), () -> entitlements.takeAttempt(guest, exam), PaidErrors::notEntitled)
                .map(started -> started.getSessionId());
    }

    private static String code(Throwable error) {
        return ((ApplicationException) error).getErrorCode();
    }

    @Test
    void anEntitledGuestStartsOnceAndTheAttemptIsTaken() {
        long exam = exam(true);
        UUID guest = UUID.randomUUID();
        entitle(guest, exam, 1);
        String session = startAs(guest, exam).block();
        assertThat(session).isNotNull();
        assertThat(stack.scalar("SELECT attempts_left FROM entitlements WHERE guest_id = '" + guest + "'")).isZero();
        assertThat(stack.scalar("SELECT count(*) FROM entitlements WHERE guest_id = '" + guest + "' AND status = 'USED'")).isEqualTo(1);
        // The open session again, without an attempt
        assertThat(startAs(guest, exam).block()).isEqualTo(session);
        // Finished: no attempt left
        stack.db.sql("UPDATE exam_sessions SET status = 'COMPLETED' WHERE session_id = '" + session + "'").then().block();
        assertThatThrownBy(() -> startAs(guest, exam).block()).satisfies(error -> assertThat(code(error)).isEqualTo("NOT_ENTITLED"));
        assertThat(stack.scalar("SELECT count(*) FROM exam_sessions WHERE user_id = '" + guest + "'")).isEqualTo(1);
    }

    @Test
    void twoStartsAtOnceGiveOneSessionAndTakeOneAttempt() {
        long exam = exam(true);
        UUID guest = UUID.randomUUID();
        entitle(guest, exam, 2);
        List<String> sessions = Flux.range(0, 2)
                .flatMap(i -> Mono.fromCallable(() -> startAs(guest, exam).block()).subscribeOn(Schedulers.boundedElastic()), 2)
                .collectList().block();
        assertThat(sessions).hasSize(2).containsOnly(sessions.get(0));
        assertThat(stack.scalar("SELECT attempts_left FROM entitlements WHERE guest_id = '" + guest + "'")).isEqualTo(1);
        assertThat(stack.scalar("SELECT count(*) FROM exam_sessions WHERE user_id = '" + guest + "'")).isEqualTo(1);
    }

    @Test
    void noEntitlementNoSessionAndOnlyPaidOpenExams() {
        long paid = exam(true);
        UUID guest = UUID.randomUUID();
        assertThatThrownBy(() -> startAs(guest, paid).block()).satisfies(error -> assertThat(code(error)).isEqualTo("NOT_ENTITLED"));
        assertThat(stack.scalar("SELECT count(*) FROM exam_sessions WHERE user_id = '" + guest + "'")).isZero();

        long audience = exam(false);
        entitle(guest, audience, 1);
        assertThatThrownBy(() -> startAs(guest, audience).block()).satisfies(error -> assertThat(code(error)).isEqualTo("EXAM_NOT_FOUND"));
        assertThat(stack.scalar("SELECT attempts_left FROM entitlements WHERE guest_id = '" + guest + "' AND exam_id = " + audience)).isEqualTo(1);
    }

    @Test
    void aRegisteredParticipantStartsAnAudienceExamAsBefore() {
        long audience = exam(false);
        UUID student = UUID.randomUUID();
        var started = service.startExam(audience, student, "STUDENT", new StartExamRequest()).block();
        assertThat(started.getSessionId()).isNotNull();
        // And never a PAID_OPEN exam through the audience
        long paid = exam(true);
        assertThatThrownBy(() -> service.startExam(paid, student, "STUDENT", new StartExamRequest()).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("EXAM_NOT_FOUND"));
    }
}
