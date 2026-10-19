package com.examportal.exam.service;

import com.examportal.common.domain.dto.BlockResultResponse;
import com.examportal.common.domain.dto.CreateExamRequest;
import com.examportal.common.domain.dto.ExamBlockDTO;
import com.examportal.common.domain.dto.ExamResponse;
import com.examportal.common.domain.dto.SessionResultResponse;
import com.examportal.exam.repository.BlockResultRepository;
import com.examportal.exam.repository.UserAnswerRepository;
import com.examportal.exam.structure.ExamState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.testcontainers.DockerClientFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        answers = new AnswerSubmissionService(stack.exams, stack.questions, stack.options, stack.sessions,
                repositories.getRepository(UserAnswerRepository.class), stack.cache, stack.accessPolicy, stack.tx, blockResults);
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
    void noBreakdownMeansNoBlocks() {
        Taken taken = taken("T-b12a block result hidden", false);

        SessionResultResponse result = answers.getSessionResult(taken.sessionId(), taken.participant()).block();

        assertThat(result.getResultsAvailable()).isTrue();
        assertThat(result.getBlocks()).isNull();
    }
}
