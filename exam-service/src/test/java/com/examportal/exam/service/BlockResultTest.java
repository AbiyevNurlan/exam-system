package com.examportal.exam.service;

import com.examportal.common.domain.dto.BlockResultResponse;
import com.examportal.common.domain.dto.CreateExamRequest;
import com.examportal.common.domain.dto.ExamBlockDTO;
import com.examportal.common.domain.dto.ExamResponse;
import com.examportal.common.domain.dto.SessionResultResponse;
import com.examportal.common.domain.dto.SessionReviewResponse;
import com.examportal.common.exception.ApplicationException;
import com.examportal.exam.controller.ExamController;
import com.examportal.exam.repository.BlockResultRepository;
import com.examportal.exam.repository.ExamResultsRepository;
import com.examportal.exam.repository.UserAnswerRepository;
import com.examportal.exam.structure.ExamState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.testcontainers.DockerClientFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B12a on a real PostgreSQL: the result of a finished session per block. The participant gets only the blocks
 * shown to participants, and nothing when the exam shows no breakdown; the review of ADMIN and the owner gets
 * every block with showResultToParticipant. Skipped without Docker.
 */
class BlockResultTest {

    private static ExamTestStack stack;
    private static ExamService service;
    private static BlockResultRepository blockResults;
    private static AnswerSubmissionService answers;
    private static ExamResultsService results;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @BeforeAll
    static void start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        stack = ExamTestStack.start();
        service = stack.examService(new ExamFeatures(false, false));
        blockResults = new BlockResultRepository(stack.db);
        R2dbcRepositoryFactory repositories = new R2dbcRepositoryFactory(new R2dbcEntityTemplate(
                new io.r2dbc.postgresql.PostgresqlConnectionFactory(io.r2dbc.postgresql.PostgresqlConnectionConfiguration.builder()
                        .host(stack.postgres.getHost()).port(stack.postgres.getMappedPort(5432))
                        .database(stack.postgres.getDatabaseName()).username(stack.postgres.getUsername())
                        .password(stack.postgres.getPassword()).build())));
        UserAnswerRepository userAnswers = repositories.getRepository(UserAnswerRepository.class);
        answers = new AnswerSubmissionService(stack.exams, stack.questions, stack.options, stack.sessions,
                userAnswers, stack.cache, stack.accessPolicy, stack.tx, blockResults, new AnswerTokens("test-secret"), stack.blockSessions());
        ParticipantDirectory directory = mock(ParticipantDirectory.class);
        when(directory.names(any())).thenReturn(Mono.just(Map.of()));
        results = new ExamResultsService(new ExamResultsRepository(stack.db), stack.sessions, stack.questions, stack.options,
                userAnswers, stack.accessPolicy, directory, new FraudRiskPolicy(5, 15, 30), stack.examMedia, stack.groupAudit,
                blockResults);
    }

    @AfterAll
    static void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    private record Taken(ExamResponse exam, ExamState state, long sessionDbId, String sessionId, UUID participant) {
    }

    /**
     * Riyaziyyat (2.50 + 1.50, shown), Məntiq (2, hidden); the participant answers the first question right,
     * the second wrong and leaves the third
     */
    private static Taken taken(String title, boolean breakdown) {
        CreateExamRequest request = ExamBlocksServiceTest.create(title);
        request.setShowBlockBreakdown(breakdown);
        request.setBlocks(List.of(
                ExamBlockDTO.builder().title("Riyaziyyat").questions(new ArrayList<>(List.of(
                        ExamHistoryTest.question(null, "2+2?", 0, "2.50"),
                        ExamHistoryTest.question(null, "3+3?", 1, "1.50")))).build(),
                ExamBlockDTO.builder().title("Məntiq").showResultToParticipant(false).questions(new ArrayList<>(List.of(
                        ExamHistoryTest.question(null, "Növbəti?", 2, "2")))).build()));
        ExamResponse exam = service.createExam(request, ExamHistoryTest.nurlan()).block();
        ExamState state = stack.states.load(exam.getId()).block();
        UUID participant = UUID.randomUUID();
        String sessionId = UUID.randomUUID().toString();
        long sessionDbId = stack.db.sql("INSERT INTO exam_sessions (session_id, exam_id, user_id, status, submitted_at, "
                        + "score, total_points, percentage, passed) VALUES (:s, :e, :u, 'COMPLETED', CURRENT_TIMESTAMP, "
                        + "2.50, 6, 41.67, false) RETURNING id")
                .bind("s", sessionId).bind("e", exam.getId()).bind("u", participant)
                .map(row -> row.get(0, Long.class)).one().block();
        answer(sessionDbId, state.questions().get(0), true, "2.50");
        answer(sessionDbId, state.questions().get(1), false, "0");
        return new Taken(exam, state, sessionDbId, sessionId, participant);
    }

    private static void answer(long sessionDbId, ExamState.QuestionState question, boolean correct, String points) {
        stack.db.sql("INSERT INTO user_answers (session_id, question_id, selected_option_ids, is_correct, points_earned) "
                        + "VALUES (:s, :q, ARRAY[:o]::bigint[], :c, :p::numeric)")
                .bind("s", sessionDbId).bind("q", question.id()).bind("o", question.options().get(0).id())
                .bind("c", correct).bind("p", points).then().block();
    }

    @Test
    void everyBlockForTheManagers() {
        Taken taken = taken("T-b12a block result", true);

        List<BlockResultResponse> blocks = blockResults.forSession(taken.exam().getId(), taken.sessionDbId()).collectList().block();

        assertThat(blocks).extracting(BlockResultResponse::getTitle).containsExactly("Riyaziyyat", "Məntiq");
        BlockResultResponse math = blocks.get(0);
        assertThat(math.getBlockId()).isEqualTo(taken.state().blocks().get(0).id());
        assertThat(math.getTotalQuestions()).isEqualTo(2);
        assertThat(math.getAnsweredQuestions()).isEqualTo(2);
        assertThat(math.getCorrectAnswers()).isEqualTo(1);
        assertThat(math.getScore()).isEqualByComparingTo("2.50");
        assertThat(math.getMaxScore()).isEqualByComparingTo("4");
        assertThat(math.getPercentage()).isEqualByComparingTo("62.50");
        assertThat(math.getShowResultToParticipant()).isTrue();
        BlockResultResponse logic = blocks.get(1);
        assertThat(logic.getTotalQuestions()).isEqualTo(1);
        assertThat(logic.getAnsweredQuestions()).isZero();
        assertThat(logic.getCorrectAnswers()).isZero();
        assertThat(logic.getScore()).isEqualByComparingTo("0");
        assertThat(logic.getMaxScore()).isEqualByComparingTo("2");
        assertThat(logic.getPercentage()).isEqualByComparingTo("0");
        assertThat(logic.getShowResultToParticipant()).isFalse();
    }

    @Test
    void aManualGradeCountsOnceGiven() {
        Taken taken = taken("T-b12a block result graded", true);
        stack.db.sql("UPDATE user_answers SET points_earned = 0.75 WHERE session_id = :s AND question_id = :q")
                .bind("s", taken.sessionDbId()).bind("q", taken.state().questions().get(1).id()).then().block();

        BlockResultResponse math = blockResults.forSession(taken.exam().getId(), taken.sessionDbId()).blockFirst();

        assertThat(math.getScore()).isEqualByComparingTo("3.25");
        assertThat(math.getPercentage()).isEqualByComparingTo("81.25");
    }

    @Test
    void theParticipantSeesOnlyTheShownBlocks() {
        Taken taken = taken("T-b12a block result participant", true);

        SessionResultResponse result = answers.getSessionResult(taken.sessionId(), taken.participant()).block();

        assertThat(result.getResultsAvailable()).isTrue();
        assertThat(result.getBlocks()).extracting(BlockResultResponse::getTitle).containsExactly("Riyaziyyat");
        assertThat(result.getBlocks().get(0).getScore()).isEqualByComparingTo("2.50");
        // The flag is for the people who manage the exam
        assertThat(result.getBlocks().get(0).getShowResultToParticipant()).isNull();
    }

    @Test
    void nothingOfTheHiddenBlockReachesTheParticipantsJson() throws Exception {
        Taken taken = taken("T-b12a block result json", true);
        long hiddenBlock = taken.state().blocks().get(1).id();

        String json = JSON.writeValueAsString(answers.getSessionResult(taken.sessionId(), taken.participant()).block());

        assertThat(json).contains("Riyaziyyat");
        assertThat(json).doesNotContain("Məntiq").doesNotContain("\"blockId\":" + hiddenBlock)
                .doesNotContain("showResultToParticipant");
    }

    @Test
    void noBlocksWhileTheResultIsWithheld() {
        Taken taken = taken("T-b12a block result withheld", true);
        stack.db.sql("UPDATE exams SET show_results_immediately = false WHERE id = :e")
                .bind("e", taken.exam().getId()).then().block();

        SessionResultResponse result = answers.getSessionResult(taken.sessionId(), taken.participant()).block();

        assertThat(result.getResultsAvailable()).isFalse();
        assertThat(result.getBlocks()).isNull();
    }

    @Test
    void someoneElsesSessionIs404() {
        Taken taken = taken("T-b12a block result foreign", true);

        assertThatThrownBy(() -> answers.getSessionResult(taken.sessionId(), UUID.randomUUID()).block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("SESSION_NOT_FOUND"));
    }

    @Test
    void theReviewHasEveryBlockForAdminAndTheOwner() {
        Taken taken = taken("T-b12a block result review", true);

        for (SessionReviewResponse review : List.of(
                results.getReview(taken.sessionId(), UUID.randomUUID(), "ADMIN").block(),
                results.getReview(taken.sessionId(), ExamHistoryTest.nurlan().actorId(), "INSTRUCTOR").block())) {
            assertThat(review.getBlocks()).extracting(BlockResultResponse::getTitle).containsExactly("Riyaziyyat", "Məntiq");
            assertThat(review.getBlocks()).extracting(BlockResultResponse::getShowResultToParticipant).containsExactly(true, false);
            assertThat(review.getBlocks().get(0).getScore()).isEqualByComparingTo("2.50");
            assertThat(review.getBlocks().get(1).getMaxScore()).isEqualByComparingTo("2");
            // The questions of the hidden block too, each with its block
            assertThat(review.getQuestions()).extracting(SessionReviewResponse.QuestionReview::getBlockId)
                    .containsExactly(taken.state().blocks().get(0).id(), taken.state().blocks().get(0).id(),
                            taken.state().blocks().get(1).id());
        }
    }

    @Test
    void theReviewIsNotForAnotherInstructorNorForParticipants() throws Exception {
        Taken taken = taken("T-b12a block result review foreign", true);

        // B13: a session of an exam the caller does not own is the same 404 as a session that does not exist,
        // on a PUBLIC exam too (ExamAccessPolicy.findManageableSession, the owner in the SQL condition)
        assertThatThrownBy(() -> results.getReview(taken.sessionId(), UUID.randomUUID(), "INSTRUCTOR").block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("SESSION_NOT_FOUND"));
        assertThatThrownBy(() -> results.getReview(taken.sessionId(), taken.participant(), "STUDENT").block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("SESSION_NOT_FOUND"));
        assertThatThrownBy(() -> results.getReview("no-such-session", UUID.randomUUID(), "INSTRUCTOR").block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("SESSION_NOT_FOUND"));
        // The same outside the audience of a GROUP exam
        stack.db.sql("UPDATE exams SET access_type = 'GROUP' WHERE id = :e").bind("e", taken.exam().getId()).then().block();
        assertThatThrownBy(() -> results.getReview(taken.sessionId(), UUID.randomUUID(), "INSTRUCTOR").block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("SESSION_NOT_FOUND"));
        assertThatThrownBy(() -> results.getReview(taken.sessionId(), taken.participant(), "STUDENT").block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("SESSION_NOT_FOUND"));
        // And a STUDENT does not reach the service at all
        assertThat(ExamController.class.getMethod("getSessionReview", String.class,
                com.examportal.common.security.UserPrincipal.class).getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAnyRole('ADMIN', 'INSTRUCTOR')");
    }

    private static String code(Throwable error) {
        return ((ApplicationException) error).getErrorCode();
    }

    @Test
    void noBreakdownMeansNoBlocks() {
        Taken taken = taken("T-b12a block result hidden", false);

        SessionResultResponse result = answers.getSessionResult(taken.sessionId(), taken.participant()).block();

        assertThat(result.getResultsAvailable()).isTrue();
        assertThat(result.getBlocks()).isNull();
    }
}
