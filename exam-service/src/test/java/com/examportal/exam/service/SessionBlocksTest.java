package com.examportal.exam.service;

import com.examportal.common.domain.dto.CreateExamRequest;
import com.examportal.common.domain.dto.ExamBlockDTO;
import com.examportal.common.domain.dto.ExamResponse;
import com.examportal.common.domain.dto.QuestionResponse;
import com.examportal.common.domain.dto.SessionBlocksResponse;
import com.examportal.common.domain.dto.SubmitAnswerRequest;
import com.examportal.common.domain.entity.Exam;
import com.examportal.common.domain.entity.ExamSession;
import com.examportal.common.domain.entity.TimingMode;
import com.examportal.common.exception.ApplicationException;
import com.examportal.exam.repository.BlockResultRepository;
import com.examportal.exam.repository.UserAnswerRepository;
import com.examportal.exam.session.BlockTimeline.Phase;
import com.examportal.exam.session.SessionBlockService;
import com.examportal.exam.structure.ExamState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.testcontainers.DockerClientFactory;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B12b on a real PostgreSQL (exam V23): a session goes through the blocks of its exam. Only the open block can be
 * seen and answered; a break closes everything; a block that was left or ran out stays closed; a block without
 * free navigation goes forward only; the end of the session follows the timeline; an exam of one block with the
 * exam timer and free navigation is untouched. Skipped without Docker.
 */
class SessionBlocksTest {

    private static ExamTestStack stack;
    private static ExamService service;
    private static AnswerSubmissionService answers;
    private static SessionBlockService blocks;

    @BeforeAll
    static void start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        stack = ExamTestStack.start();
        service = stack.examService(new ExamFeatures(true, false));
        blocks = stack.blockSessions();
        R2dbcRepositoryFactory repositories = new R2dbcRepositoryFactory(new R2dbcEntityTemplate(
                new io.r2dbc.postgresql.PostgresqlConnectionFactory(io.r2dbc.postgresql.PostgresqlConnectionConfiguration.builder()
                        .host(stack.postgres.getHost()).port(stack.postgres.getMappedPort(5432))
                        .database(stack.postgres.getDatabaseName()).username(stack.postgres.getUsername())
                        .password(stack.postgres.getPassword()).build())));
        answers = new AnswerSubmissionService(stack.exams, stack.questions, stack.options, stack.sessions,
                repositories.getRepository(UserAnswerRepository.class), stack.cache, stack.accessPolicy, stack.tx,
                new BlockResultRepository(stack.db), new AnswerTokens("test-secret"), blocks);
    }

    @AfterAll
    static void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    private record Taken(ExamState state, Exam exam, String sessionId, long sessionDbId, UUID participant) {

        long question(int block, int index) {
            return state.blocks().get(block).questions().get(index).id();
        }

        long option(int block, int index) {
            return state.blocks().get(block).questions().get(index).options().get(0).id();
        }
    }

    /**
     * Riyaziyyat (2 questions, 30 minutes, a break of 5), Məntiq (3 questions, 20 minutes, forward only when asked)
     */
    private static Taken taken(String title, TimingMode mode, boolean returnAllowed, boolean skipBreak, boolean logicForwardOnly) {
        CreateExamRequest request = ExamBlocksServiceTest.create(title);
        request.setTimingMode(mode);
        request.setAllowReturnToPreviousBlocks(returnAllowed);
        request.setAllowSkipBreak(skipBreak);
        request.setDurationMinutes(60);
        request.setBlocks(List.of(
                ExamBlockDTO.builder().title("Riyaziyyat").timeLimitMinutes(mode == TimingMode.BLOCK_TIMERS ? 30 : null)
                        .breakAfterMinutes(mode == TimingMode.BLOCK_TIMERS ? 5 : 0)
                        .questions(new ArrayList<>(List.of(
                                ExamHistoryTest.question(null, "2+2?", 0, "1"),
                                ExamHistoryTest.question(null, "3+3?", 0, "1")))).build(),
                ExamBlockDTO.builder().title("Məntiq").timeLimitMinutes(mode == TimingMode.BLOCK_TIMERS ? 20 : null)
                        .freeNavigation(!logicForwardOnly)
                        .questions(new ArrayList<>(List.of(
                                ExamHistoryTest.question(null, "Növbəti?", 0, "1"),
                                ExamHistoryTest.question(null, "Sonrakı?", 0, "1"),
                                ExamHistoryTest.question(null, "Axırıncı?", 0, "1")))).build()));
        ExamResponse created = service.createExam(request, ExamHistoryTest.nurlan()).block();
        Exam exam = stack.exams.findById(created.getId()).block();
        UUID participant = UUID.randomUUID();
        String sessionId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        long sessionDbId = stack.db.sql("INSERT INTO exam_sessions (session_id, exam_id, user_id, status, start_time, end_time) "
                        + "VALUES (:s, :e, :u, 'IN_PROGRESS', :start, :end) RETURNING id")
                .bind("s", sessionId).bind("e", exam.getId()).bind("u", participant)
                .bind("start", now).bind("end", now.plusMinutes(exam.getDurationMinutes()))
                .map(row -> row.get(0, Long.class)).one().block();
        return new Taken(stack.states.load(exam.getId()).block(), exam, sessionId, sessionDbId, participant);
    }

    private static Taken timers(String title) {
        return taken(title, TimingMode.BLOCK_TIMERS, false, true, false);
    }

    private static ExamSession session(Taken taken) {
        return stack.sessions.findBySessionId(taken.sessionId()).block();
    }

    private static SessionBlockService.Layout layout(Taken taken) {
        return blocks.layout(taken.exam()).block().orElseThrow();
    }

    private static void answer(Taken taken, int block, int index) {
        answers.submitAnswer(taken.sessionId(), SubmitAnswerRequest.builder().questionId(taken.question(block, index))
                .selectedOptionIds(List.of(taken.option(block, index))).build(), taken.participant()).block();
    }

    private static String refused(Taken taken, int block, int index) {
        try {
            answer(taken, block, index);
        } catch (ApplicationException error) {
            return error.getErrorCode();
        }
        return "ACCEPTED";
    }

    // The session started this many minutes earlier than it did, with everything stored about it
    private static void earlier(Taken taken, int minutes) {
        stack.db.sql("UPDATE exam_sessions SET start_time = start_time - make_interval(mins => :m), "
                        + "end_time = end_time - make_interval(mins => :m) WHERE id = :id")
                .bind("m", minutes).bind("id", taken.sessionDbId()).then().block();
        stack.db.sql("UPDATE session_block_progress SET started_at = started_at - make_interval(mins => :m), "
                        + "deadline = deadline - make_interval(mins => :m), finished_at = finished_at - make_interval(mins => :m), "
                        + "break_until = break_until - make_interval(mins => :m) WHERE session_id = :id")
                .bind("m", minutes).bind("id", taken.sessionDbId()).then().block();
    }

    private static long rows(Taken taken) {
        return stack.scalar("SELECT COUNT(*) FROM session_block_progress WHERE session_id = " + taken.sessionDbId());
    }

    @Test
    void onlyTheCurrentBlockCanBeSeenAndAnswered() {
        Taken taken = timers("T-b12b current block");

        SessionBlocksResponse screen = service.sessionBlocks(taken.sessionId(), taken.participant()).block();

        assertThat(screen.getPhase()).isEqualTo("BLOCK");
        assertThat(screen.getCurrent()).isEqualTo(1);
        assertThat(screen.getSequential()).isTrue();
        assertThat(screen.getBlocks()).extracting(SessionBlocksResponse.Block::getStatus).containsExactly("CURRENT", "UPCOMING");
        assertThat(screen.getBlocks()).extracting(SessionBlocksResponse.Block::getQuestionCount).containsExactly(2, 3);
        assertThat(screen.getQuestions()).extracting(QuestionResponse::getId)
                .containsExactly(taken.question(0, 0), taken.question(0, 1));
        assertThat(screen.getBlockDeadline()).isEqualTo(session(taken).getStartTime().plusMinutes(30));
        // Even the questions by batch never show the next block
        assertThat(service.getNextQuestions(taken.sessionId(), 0, taken.participant()).block())
                .extracting(QuestionResponse::getBlockId).containsOnly(taken.state().blocks().get(0).id());

        answer(taken, 0, 0);
        assertThat(refused(taken, 1, 0)).isEqualTo("BLOCK_CLOSED");
    }

    @Test
    void aBreakClosesEverythingAndTheBlockThatWasLeftStaysClosed() {
        Taken taken = timers("T-b12b break");
        LocalDateTime endBefore = session(taken).getEndTime();

        SessionBlocksResponse onBreak = service.finishBlock(taken.sessionId(), taken.participant()).block();

        assertThat(onBreak.getPhase()).isEqualTo("BREAK");
        assertThat(onBreak.getQuestions()).isEmpty();
        assertThat(onBreak.getCanSkipBreak()).isTrue();
        assertThat(onBreak.getBreakUntil()).isAfter(LocalDateTime.now().plusMinutes(4));
        assertThat(refused(taken, 0, 0)).isEqualTo("SESSION_ON_BREAK");
        assertThat(refused(taken, 1, 0)).isEqualTo("SESSION_ON_BREAK");
        // Leaving 30 minutes early brings the end of the session forward
        assertThat(session(taken).getEndTime()).isBefore(endBefore.minusMinutes(25));

        SessionBlocksResponse second = service.endBreak(taken.sessionId(), taken.participant()).block();

        assertThat(second.getPhase()).isEqualTo("BLOCK");
        assertThat(second.getCurrent()).isEqualTo(2);
        assertThat(second.getBlocks()).extracting(SessionBlocksResponse.Block::getStatus).containsExactly("DONE", "CURRENT");
        assertThat(second.getQuestions()).hasSize(3);
        assertThat(refused(taken, 0, 1)).isEqualTo("BLOCK_CLOSED");
        assertThat(refused(taken, 1, 2)).isEqualTo("ACCEPTED");
    }

    @Test
    void aBreakThatMayNotBeEndedEarly() {
        Taken taken = taken("T-b12b strict break", TimingMode.BLOCK_TIMERS, false, false, false);
        service.finishBlock(taken.sessionId(), taken.participant()).block();

        assertThatThrownBy(() -> service.endBreak(taken.sessionId(), taken.participant()).block())
                .satisfies(error -> assertThat(((ApplicationException) error).getErrorCode()).isEqualTo("BREAK_NOT_SKIPPABLE"));
    }

    @Test
    void theTimeRunsOnWhileNobodyLooks() {
        Taken taken = timers("T-b12b timeline");
        blocks.current(session(taken), layout(taken)).block();

        // 40 minutes later: the first block ran out at 30, the break ended at 35, the second block runs since then
        earlier(taken, 40);
        SessionBlockService.Snapshot snapshot = blocks.current(session(taken), layout(taken)).block();

        assertThat(snapshot.phase()).isEqualTo(Phase.BLOCK);
        assertThat(snapshot.state().current()).isEqualTo(2);
        LocalDateTime start = session(taken).getStartTime();
        assertThat(snapshot.state().rows().get(0).finishedAt()).isEqualTo(start.plusMinutes(30));
        assertThat(snapshot.state().rows().get(1).startedAt()).isEqualTo(start.plusMinutes(35));
        assertThat(refused(taken, 0, 0)).isEqualTo("BLOCK_CLOSED");
        assertThat(refused(taken, 1, 0)).isEqualTo("ACCEPTED");
    }

    @Test
    void whenTheLastBlockIsOverTheSessionIsCompletedAndNothingIsTakenAnyMore() {
        Taken taken = timers("T-b12b over");
        blocks.current(session(taken), layout(taken)).block();
        answer(taken, 0, 0);

        earlier(taken, 54);
        // At 54 minutes the second block (35..55) is still running
        assertThat(refused(taken, 1, 0)).isEqualTo("ACCEPTED");
        earlier(taken, 2);

        assertThat(refused(taken, 1, 1)).isEqualTo("SESSION_EXPIRED");
        assertThat(session(taken).getStatus().name()).isEqualTo("COMPLETED");
        assertThat(session(taken).getScore()).isEqualByComparingTo("2");
    }

    @Test
    void aForwardOnlyBlockClosesTheQuestionsBehind() {
        Taken taken = taken("T-b12b forward only", TimingMode.BLOCK_TIMERS, false, true, true);
        service.finishBlock(taken.sessionId(), taken.participant()).block();
        service.endBreak(taken.sessionId(), taken.participant()).block();
        SessionBlockService.Layout layout = layout(taken);
        // The order this session sees the block in (the block does not shuffle here: the stored order)
        List<Long> order = layout.order(layout.blocks().get(1), taken.sessionId());
        assertThat(order).containsExactly(taken.question(1, 0), taken.question(1, 1), taken.question(1, 2));

        answer(taken, 1, 1);
        assertThat(refused(taken, 1, 0)).isEqualTo("QUESTION_LOCKED");
        assertThat(refused(taken, 1, 1)).isEqualTo("ACCEPTED");

        SessionBlocksResponse moved = service.reachQuestion(taken.sessionId(), taken.question(1, 2), taken.participant()).block();
        assertThat(moved.getCursor()).isEqualTo(2);
        assertThat(moved.getBlocks().get(1).getCursor()).isEqualTo(2);
        assertThat(refused(taken, 1, 1)).isEqualTo("QUESTION_LOCKED");
    }

    @Test
    void returningAllowedUnderTheExamTimerOpensEveryBlock() {
        Taken taken = taken("T-b12b open", TimingMode.EXAM_TIMER, true, true, false);

        SessionBlocksResponse screen = service.sessionBlocks(taken.sessionId(), taken.participant()).block();

        assertThat(screen.getSequential()).isFalse();
        assertThat(screen.getBlocks()).extracting(SessionBlocksResponse.Block::getStatus).containsExactly("OPEN", "OPEN");
        assertThat(screen.getQuestions()).hasSize(5);
        assertThat(refused(taken, 1, 2)).isEqualTo("ACCEPTED");
        assertThat(refused(taken, 0, 0)).isEqualTo("ACCEPTED");
        assertThatThrownBy(() -> service.finishBlock(taken.sessionId(), taken.participant()).block())
                .satisfies(error -> assertThat(((ApplicationException) error).getErrorCode()).isEqualTo("BLOCK_NOT_FINISHABLE"));
    }

    @Test
    void underTheExamTimerWithoutReturningTheBlocksComeOneAfterAnother() {
        Taken taken = taken("T-b12b exam timer sequential", TimingMode.EXAM_TIMER, false, true, false);

        assertThat(refused(taken, 1, 0)).isEqualTo("BLOCK_CLOSED");
        SessionBlocksResponse second = service.finishBlock(taken.sessionId(), taken.participant()).block();

        // No break under the exam timer
        assertThat(second.getPhase()).isEqualTo("BLOCK");
        assertThat(second.getCurrent()).isEqualTo(2);
        assertThat(second.getBlockDeadline()).isEqualTo(session(taken).getEndTime());
        assertThat(refused(taken, 0, 0)).isEqualTo("BLOCK_CLOSED");
    }

    @Test
    void anExamOfOneOrdinaryBlockIsUntouched() {
        CreateExamRequest request = ExamBlocksServiceTest.create("T-b12b ordinary");
        request.setQuestions(new ArrayList<>(List.of(ExamHistoryTest.question(null, "2+2?", 0, "1"))));
        ExamResponse created = service.createExam(request, ExamHistoryTest.nurlan()).block();
        Exam exam = stack.exams.findById(created.getId()).block();

        assertThat(blocks.layout(exam).block()).isEmpty();
        assertThatThrownBy(() -> service.sessionBlocks("whatever", UUID.randomUUID()).block())
                .satisfies(error -> assertThat(((ApplicationException) error).getErrorCode()).isEqualTo("SESSION_NOT_FOUND"));
    }

    @Test
    void someoneElsesSessionIs404() {
        Taken taken = timers("T-b12b foreign");

        assertThatThrownBy(() -> service.finishBlock(taken.sessionId(), UUID.randomUUID()).block())
                .satisfies(error -> assertThat(((ApplicationException) error).getErrorCode()).isEqualTo("SESSION_NOT_FOUND"));
        assertThat(rows(taken)).isZero();
    }
}
