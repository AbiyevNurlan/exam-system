package com.examportal.exam.service;

import com.examportal.common.domain.dto.CreateExamRequest;
import com.examportal.common.domain.dto.ExamBlockDTO;
import com.examportal.common.domain.dto.ExamResponse;
import com.examportal.common.domain.dto.OptionDTO;
import com.examportal.common.domain.dto.QuestionDTO;
import com.examportal.common.domain.dto.QuestionResponse;
import com.examportal.common.domain.dto.SavedAnswerResponse;
import com.examportal.common.domain.dto.ScaleSettings;
import com.examportal.common.domain.dto.SessionResultResponse;
import com.examportal.common.domain.dto.SubmitAnswerRequest;
import com.examportal.common.domain.dto.UpdateExamRequest;
import com.examportal.common.domain.entity.Exam;
import com.examportal.common.domain.entity.QuestionType;
import com.examportal.common.exception.ApplicationException;
import com.examportal.exam.repository.BlockResultRepository;
import com.examportal.exam.repository.UserAnswerRepository;
import com.examportal.exam.structure.ExamState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.testcontainers.DockerClientFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LINEAR_SCALE ("Diapazon") on a real PostgreSQL (exam V22): the scale is stored on the question and the right
 * number as its only option; a session gets the scale but never the right number; an answer is one whole number of
 * the scale, stored as the plain number and graded exactly; a change of the scale is a row of the history.
 * Skipped without Docker.
 */
class LinearScaleTest {

    private static ExamTestStack stack;
    private static ExamService service;
    private static AnswerSubmissionService answers;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @BeforeAll
    static void start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        stack = ExamTestStack.start();
        service = stack.examService(new ExamFeatures(false, false));
        R2dbcRepositoryFactory repositories = new R2dbcRepositoryFactory(new R2dbcEntityTemplate(
                new io.r2dbc.postgresql.PostgresqlConnectionFactory(io.r2dbc.postgresql.PostgresqlConnectionConfiguration.builder()
                        .host(stack.postgres.getHost()).port(stack.postgres.getMappedPort(5432))
                        .database(stack.postgres.getDatabaseName()).username(stack.postgres.getUsername())
                        .password(stack.postgres.getPassword()).build())));
        UserAnswerRepository userAnswers = repositories.getRepository(UserAnswerRepository.class);
        answers = new AnswerSubmissionService(stack.exams, stack.questions, stack.options, stack.sessions,
                userAnswers, stack.cache, stack.accessPolicy, stack.tx, new BlockResultRepository(stack.db),
                new AnswerTokens("test-secret"));
    }

    @AfterAll
    static void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    static QuestionDTO scale(int min, int max, String right) {
        return QuestionDTO.builder().questionText("Bu mövzunu nə qədər yaxşı bilirsiniz?").questionType(QuestionType.LINEAR_SCALE)
                .points(new BigDecimal("2")).orderIndex(0).isRequired(true)
                .scale(ScaleSettings.builder().min(min).max(max).minLabel(" Heç ").maxLabel("Əla").build())
                .options(new ArrayList<>(List.of(OptionDTO.builder().optionText(right).orderIndex(0).build())))
                .build();
    }

    private static ExamResponse create(String title, QuestionDTO question) {
        CreateExamRequest request = ExamBlocksServiceTest.create(title);
        request.setBlocks(List.of(ExamBlockDTO.builder().title("Əsas blok").questions(new ArrayList<>(List.of(question))).build()));
        return service.createExam(request, ExamHistoryTest.nurlan()).block();
    }

    private record Session(String sessionId, UUID participant, QuestionResponse question) {
    }

    private static Session session(ExamResponse exam) {
        UUID participant = UUID.randomUUID();
        String sessionId = UUID.randomUUID().toString();
        stack.db.sql("INSERT INTO exam_sessions (session_id, exam_id, user_id, status, end_time) "
                        + "VALUES (:s, :e, :u, 'IN_PROGRESS', CURRENT_TIMESTAMP + interval '1 hour')")
                .bind("s", sessionId).bind("e", exam.getId()).bind("u", participant).then().block();
        Exam entity = stack.exams.findById(exam.getId()).block();
        QuestionResponse question = service.prepareQuestionsForSession(entity, sessionId).block().get(0);
        return new Session(sessionId, participant, question);
    }

    private static void answer(Session session, String value) {
        answers.submitAnswer(session.sessionId(), SubmitAnswerRequest.builder().questionId(session.question().getId())
                .textAnswer(value).build(), session.participant()).block();
    }

    private static String code(Throwable error) {
        return ((ApplicationException) error).getErrorCode();
    }

    @Test
    void theScaleAndTheRightNumberAreStoredAndShownToTheEditor() {
        ExamResponse exam = create("T-qt scale stored", scale(1, 10, " 07 "));
        QuestionResponse editor = exam.getQuestions().get(0);
        // Labels are stored trimmed, blank ones as null
        assertThat(editor.getScale()).isEqualTo(ScaleSettings.builder().min(1).max(10).minLabel("Heç").maxLabel("Əla").build());
        // The right number is the only option, stored as the plain number and marked correct
        assertThat(editor.getOptions()).singleElement().satisfies(o -> {
            assertThat(o.getOptionText()).isEqualTo("7");
            assertThat(o.getIsCorrect()).isTrue();
        });
        ExamState.QuestionState stored = stack.states.load(exam.getId()).block().questions().get(0);
        assertThat(stored.scale()).isEqualTo(new ExamState.ScaleState(1, 10, "Heç", "Əla"));
        assertThat(ExamReadinessTestAccess.codes(stored)).isEmpty();
    }

    @Test
    void aSessionGetsTheScaleButNeverTheRightNumber() throws Exception {
        Session session = session(create("T-qt scale session", scale(0, 5, "4")));
        assertThat(session.question().getScale().getMin()).isZero();
        assertThat(session.question().getScale().getMax()).isEqualTo(5);
        assertThat(session.question().getScale().getMaxLabel()).isEqualTo("Əla");
        assertThat(session.question().getOptions()).isNullOrEmpty();
        String json = JSON.writeValueAsString(session.question());
        assertThat(json).doesNotContain("isCorrect").doesNotContain("\"optionText\"");
    }

    @Test
    void theRightNumberEarnsThePointsAWrongOneNothing() {
        ExamResponse exam = create("T-qt scale graded", scale(1, 10, "7"));
        Session right = session(exam);
        answer(right, " 07");
        SavedAnswerResponse saved = answers.getSavedAnswers(right.sessionId(), right.participant()).block().get(0);
        assertThat(saved.getTextAnswer()).isEqualTo("7");
        SessionResultResponse result = answers.submitExam(right.sessionId(), right.participant()).block();
        assertThat(result.getCorrectAnswers()).isEqualTo(1);
        assertThat(result.getTotalScore()).isEqualByComparingTo("2");

        Session wrong = session(exam);
        answer(wrong, "6");
        SessionResultResponse wrongResult = answers.submitExam(wrong.sessionId(), wrong.participant()).block();
        assertThat(wrongResult.getCorrectAnswers()).isZero();
        assertThat(wrongResult.getTotalScore()).isEqualByComparingTo("0");
    }

    @Test
    void anAnswerOffTheScaleIsRefusedAndNothingIsStored() {
        Session session = session(create("T-qt scale refused", scale(1, 5, "3")));
        for (String bad : List.of("0", "6", "2.5", "abc", "-1", "100")) {
            assertThatThrownBy(() -> answer(session, bad))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("ANSWER_SCALE_OUT_OF_RANGE"));
        }
        assertThatThrownBy(() -> answer(session, " "))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ANSWER_SELECTION_REQUIRED"));
        assertThat(answers.getSavedAnswers(session.sessionId(), session.participant()).block()).isEmpty();
    }

    @Test
    void anIncompleteScaleIsRefusedOnSaveAndNotReadyToPublish() {
        assertThatThrownBy(() -> create("T-qt scale no scale", QuestionDTO.builder().questionText("q")
                .questionType(QuestionType.LINEAR_SCALE).points(BigDecimal.ONE).orderIndex(0)
                .options(new ArrayList<>(List.of(OptionDTO.builder().optionText("3").orderIndex(0).build()))).build()))
                .hasMessageContaining("lowest and highest number");
        assertThatThrownBy(() -> create("T-qt scale off", scale(1, 5, "8"))).hasMessageContaining("whole number from 1 to 5");
        assertThatThrownBy(() -> create("T-qt scale min", scale(2, 5, "3"))).hasMessageContaining("0 or 1");
        QuestionDTO two = scale(1, 5, "3");
        two.getOptions().add(OptionDTO.builder().optionText("4").orderIndex(1).build());
        assertThatThrownBy(() -> create("T-qt scale two", two)).hasMessageContaining("exactly one right number");

        // Rows the validator never saw: no scale, or a right number off the scale
        ExamState.QuestionState noScale = new ExamState.QuestionState(1L, 10L, 0, "q", QuestionType.LINEAR_SCALE,
                BigDecimal.ONE, false, true, null, List.of(), List.of(new ExamState.OptionState(1L, 0, "3", null, true, List.of())));
        assertThat(ExamReadinessTestAccess.codes(noScale)).contains("LINEAR_SCALE_INCOMPLETE");
        ExamState.QuestionState off = new ExamState.QuestionState(1L, 10L, 0, "q", QuestionType.LINEAR_SCALE,
                BigDecimal.ONE, false, true, null, List.of(), List.of(new ExamState.OptionState(1L, 0, "9", null, true, List.of())),
                new ExamState.ScaleState(1, 5, null, null));
        assertThat(ExamReadinessTestAccess.codes(off)).contains("LINEAR_SCALE_INCOMPLETE");
    }

    @Test
    void theDatabaseKeepsAScaleOnScaleQuestionsOnly() {
        ExamResponse exam = create("T-qt scale check", scale(1, 5, "3"));
        Long id = exam.getQuestions().get(0).getId();
        assertThatThrownBy(() -> stack.db.sql("UPDATE questions SET scale_max = 11 WHERE id = " + id).then().block())
                .hasMessageContaining("chk_questions_scale_range");
        assertThatThrownBy(() -> stack.db.sql("UPDATE questions SET question_type = 'MULTIPLE_CHOICE' WHERE id = " + id).then().block())
                .hasMessageContaining("chk_questions_scale_only_linear");
    }

    @Test
    void aChangeOfTheScaleIsARowOfTheHistoryAndAChangeOfTypeClearsIt() {
        ExamResponse exam = create("T-qt scale history", scale(1, 5, "3"));
        QuestionDTO changed = scale(1, 7, "6");
        changed.setId(exam.getQuestions().get(0).getId());
        changed.getOptions().get(0).setId(exam.getQuestions().get(0).getOptions().get(0).getId());
        UpdateExamRequest request = ExamBlocksServiceTest.update(exam);
        request.setQuestions(new ArrayList<>(List.of(changed)));
        ExamResponse updated = service.updateExam(exam.getId(), request, ExamHistoryTest.nurlan()).block();
        assertThat(updated.getQuestions().get(0).getScale().getMax()).isEqualTo(7);
        String changes = stack.db.sql("SELECT string_agg(changes::text, ' ') AS c FROM exam_revisions WHERE exam_id = :e")
                .bind("e", exam.getId()).map(row -> row.get("c", String.class)).one().block();
        assertThat(changes).contains("\"scale\"").contains("\"max\": 7");

        // Another type: the scale columns go back to NULL (the CHECK of V22 would refuse anything else)
        QuestionDTO choice = QuestionDTO.builder().id(changed.getId()).questionText("Seçin").questionType(QuestionType.MULTIPLE_CHOICE)
                .points(BigDecimal.ONE).orderIndex(0).isRequired(true).scale(ScaleSettings.builder().min(1).max(5).build())
                .options(new ArrayList<>(List.of(OptionDTO.builder().optionText("A").isCorrect(true).orderIndex(0).build(),
                        OptionDTO.builder().optionText("B").isCorrect(false).orderIndex(1).build())))
                .build();
        UpdateExamRequest toChoice = ExamBlocksServiceTest.update(updated);
        toChoice.setQuestions(new ArrayList<>(List.of(choice)));
        ExamResponse asChoice = service.updateExam(exam.getId(), toChoice, ExamHistoryTest.nurlan()).block();
        assertThat(asChoice.getQuestions().get(0).getScale()).isNull();
        assertThat(stack.scalar("SELECT count(*) FROM questions WHERE id = " + changed.getId() + " AND scale_min IS NULL")).isEqualTo(1);
    }
}
