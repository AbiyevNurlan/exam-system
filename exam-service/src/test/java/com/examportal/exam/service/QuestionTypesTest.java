package com.examportal.exam.service;

import com.examportal.common.domain.dto.CreateExamRequest;
import com.examportal.common.domain.dto.ExamBlockDTO;
import com.examportal.common.domain.dto.ExamResponse;
import com.examportal.common.domain.dto.OptionDTO;
import com.examportal.common.domain.dto.OptionResponse;
import com.examportal.common.domain.dto.QuestionDTO;
import com.examportal.common.domain.dto.QuestionResponse;
import com.examportal.common.domain.dto.SavedAnswerResponse;
import com.examportal.common.domain.dto.SessionResultResponse;
import com.examportal.common.domain.dto.SubmitAnswerRequest;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ORDERING, MATCHING and SHORT_ANSWER on a real PostgreSQL: stored with their answer key, sent to a session with
 * ids of that session only (nothing a participant can sort or pair), answered, restored after a reload and graded
 * all-or-nothing. Skipped without Docker.
 */
class QuestionTypesTest {

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

    private static QuestionDTO ordering() {
        List<OptionDTO> items = new ArrayList<>();
        String[] steps = {"Səhər", "Günorta", "Axşam", "Gecə"};
        for (int i = 0; i < steps.length; i++) {
            items.add(OptionDTO.builder().optionText(steps[i]).orderIndex(i).build());
        }
        return QuestionDTO.builder().questionText("Günün hissələrini sıralayın").questionType(QuestionType.ORDERING)
                .points(new BigDecimal("2")).orderIndex(0).isRequired(true).options(items).build();
    }

    private static QuestionDTO matching() {
        List<OptionDTO> pairs = new ArrayList<>();
        String[][] capitals = {{"Azərbaycan", "Bakı"}, {"Gürcüstan", "Tbilisi"}, {"Türkiyə", "Ankara"}};
        for (int i = 0; i < capitals.length; i++) {
            pairs.add(OptionDTO.builder().optionText(capitals[i][0]).matchText(capitals[i][1]).orderIndex(i).build());
        }
        return QuestionDTO.builder().questionText("Ölkəni paytaxtı ilə uyğunlaşdırın").questionType(QuestionType.MATCHING)
                .points(new BigDecimal("3")).orderIndex(1).isRequired(true).options(pairs).build();
    }

    private static QuestionDTO shortAnswer() {
        return QuestionDTO.builder().questionText("Azərbaycanın paytaxtı?").questionType(QuestionType.SHORT_ANSWER)
                .points(new BigDecimal("1")).orderIndex(2).isRequired(true)
                .options(new ArrayList<>(List.of(OptionDTO.builder().optionText("Bakı").orderIndex(0).build(),
                        OptionDTO.builder().optionText("Baku").orderIndex(1).build())))
                .build();
    }

    private record Started(ExamResponse exam, ExamState state, String sessionId, UUID participant,
                           Map<QuestionType, QuestionResponse> questions) {
    }

    private static Started started(String title) {
        CreateExamRequest request = ExamBlocksServiceTest.create(title);
        request.setBlocks(List.of(ExamBlockDTO.builder().title("Əsas blok")
                .questions(new ArrayList<>(List.of(ordering(), matching(), shortAnswer()))).build()));
        ExamResponse exam = service.createExam(request, ExamHistoryTest.nurlan()).block();
        ExamState state = stack.states.load(exam.getId()).block();
        UUID participant = UUID.randomUUID();
        String sessionId = UUID.randomUUID().toString();
        stack.db.sql("INSERT INTO exam_sessions (session_id, exam_id, user_id, status, end_time) "
                        + "VALUES (:s, :e, :u, 'IN_PROGRESS', CURRENT_TIMESTAMP + interval '1 hour')")
                .bind("s", sessionId).bind("e", exam.getId()).bind("u", participant).then().block();
        Exam entity = stack.exams.findById(exam.getId()).block();
        Map<QuestionType, QuestionResponse> questions = service.prepareQuestionsForSession(entity, sessionId).block().stream()
                .collect(Collectors.toMap(QuestionResponse::getQuestionType, q -> q));
        return new Started(exam, state, sessionId, participant, questions);
    }

    private static ExamState.QuestionState stored(Started started, QuestionType type) {
        return started.state().questions().stream().filter(q -> q.questionType() == type).findFirst().orElseThrow();
    }

    private static String code(Throwable error) {
        return ((ApplicationException) error).getErrorCode();
    }

    private static void submit(Started started, SubmitAnswerRequest request) {
        answers.submitAnswer(started.sessionId(), request, started.participant()).block();
    }

    @Test
    void theAnswerKeyIsStoredAndShownToTheEditor() {
        Started started = started("T-qt stored");
        ExamState.QuestionState matching = stored(started, QuestionType.MATCHING);
        assertThat(matching.options()).extracting(ExamState.OptionState::optionText, ExamState.OptionState::matchText)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Azərbaycan", "Bakı"),
                        org.assertj.core.groups.Tuple.tuple("Gürcüstan", "Tbilisi"),
                        org.assertj.core.groups.Tuple.tuple("Türkiyə", "Ankara"));
        // Every item, pair and accepted answer is part of the key
        assertThat(started.state().questions()).allSatisfy(q -> assertThat(q.options()).allMatch(ExamState.OptionState::correct));
        assertThat(stored(started, QuestionType.ORDERING).options()).allMatch(o -> o.matchText() == null);
        QuestionResponse editor = started.exam().getQuestions().stream()
                .filter(q -> q.getQuestionType() == QuestionType.MATCHING).findFirst().orElseThrow();
        assertThat(editor.getOptions()).extracting(OptionResponse::getMatchText).containsExactly("Bakı", "Tbilisi", "Ankara");
    }

    @Test
    void aSessionGetsNothingItCouldSortOrPair() throws Exception {
        Started started = started("T-qt session view");
        QuestionResponse ordering = started.questions().get(QuestionType.ORDERING);
        ExamState.QuestionState storedOrdering = stored(started, QuestionType.ORDERING);
        List<Long> realIds = storedOrdering.options().stream().map(ExamState.OptionState::id).toList();
        assertThat(ordering.getOptions()).hasSize(4);
        assertThat(ordering.getOptions()).extracting(OptionResponse::getId).doesNotContainAnyElementsOf(realIds);
        assertThat(ordering.getOptions()).allMatch(o -> o.getOrderIndex() == null && o.getIsCorrect() == null);
        // Never the right order
        assertThat(ordering.getOptions()).extracting(OptionResponse::getOptionText).isNotEqualTo(List.of("Səhər", "Günorta", "Axşam", "Gecə"));

        QuestionResponse matching = started.questions().get(QuestionType.MATCHING);
        List<Long> leftIds = stored(started, QuestionType.MATCHING).options().stream().map(ExamState.OptionState::id).toList();
        assertThat(matching.getOptions()).extracting(OptionResponse::getId).containsExactlyInAnyOrderElementsOf(leftIds);
        assertThat(matching.getOptions()).allMatch(o -> o.getMatchText() == null && o.getOrderIndex() == null);
        assertThat(matching.getMatchOptions()).extracting(OptionResponse::getOptionText).containsExactlyInAnyOrder("Bakı", "Tbilisi", "Ankara");
        assertThat(matching.getMatchOptions()).extracting(OptionResponse::getId).doesNotContainAnyElementsOf(leftIds);
        assertThat(matching.getMatchOptions()).extracting(OptionResponse::getOptionText).isNotEqualTo(List.of("Bakı", "Tbilisi", "Ankara"));

        // The accepted short answers never leave the server
        assertThat(started.questions().get(QuestionType.SHORT_ANSWER).getOptions()).isNull();
        String json = JSON.writeValueAsString(started.questions().values());
        assertThat(json).doesNotContain("\"Baku\"").doesNotContain("isCorrect").doesNotContain("matchText");
        // The same ids every time the session asks
        Exam entity = stack.exams.findById(started.exam().getId()).block();
        QuestionResponse again = service.prepareQuestionsForSession(entity, started.sessionId()).block().stream()
                .filter(q -> q.getQuestionType() == QuestionType.ORDERING).findFirst().orElseThrow();
        assertThat(again.getOptions()).extracting(OptionResponse::getId)
                .containsExactlyInAnyOrderElementsOf(ordering.getOptions().stream().map(OptionResponse::getId).toList());
    }

    @Test
    void rightAnswersEarnEveryPointAndAreRestoredAfterAReload() {
        Started started = started("T-qt right");
        QuestionResponse ordering = started.questions().get(QuestionType.ORDERING);
        Map<String, Long> itemByText = ordering.getOptions().stream().collect(Collectors.toMap(OptionResponse::getOptionText, OptionResponse::getId));
        List<Long> rightOrder = List.of(itemByText.get("Səhər"), itemByText.get("Günorta"), itemByText.get("Axşam"), itemByText.get("Gecə"));
        submit(started, SubmitAnswerRequest.builder().questionId(ordering.getId()).selectedOptionIds(rightOrder).build());

        QuestionResponse matching = started.questions().get(QuestionType.MATCHING);
        Map<String, Long> rightByText = matching.getMatchOptions().stream().collect(Collectors.toMap(OptionResponse::getOptionText, OptionResponse::getId));
        Map<String, String> capitals = Map.of("Azərbaycan", "Bakı", "Gürcüstan", "Tbilisi", "Türkiyə", "Ankara");
        List<SubmitAnswerRequest.Match> pairs = matching.getOptions().stream()
                .map(left -> new SubmitAnswerRequest.Match(left.getId(), rightByText.get(capitals.get(left.getOptionText()))))
                .toList();
        submit(started, SubmitAnswerRequest.builder().questionId(matching.getId()).matches(pairs).build());

        QuestionResponse shortAnswer = started.questions().get(QuestionType.SHORT_ANSWER);
        submit(started, SubmitAnswerRequest.builder().questionId(shortAnswer.getId()).textAnswer("  baku ").build());

        // A reload gives back exactly what was sent, with the ids of this session
        Map<Long, SavedAnswerResponse> saved = answers.getSavedAnswers(started.sessionId(), started.participant()).block().stream()
                .collect(Collectors.toMap(SavedAnswerResponse::getQuestionId, a -> a));
        assertThat(saved.get(ordering.getId()).getSelectedOptionIds()).isEqualTo(rightOrder);
        assertThat(saved.get(matching.getId()).getMatches()).containsExactlyInAnyOrderElementsOf(pairs);
        assertThat(saved.get(shortAnswer.getId()).getTextAnswer()).isEqualTo("baku");

        SessionResultResponse result = answers.submitExam(started.sessionId(), started.participant()).block();
        assertThat(result.getCorrectAnswers()).isEqualTo(3);
        assertThat(result.getTotalScore()).isEqualByComparingTo("6");
        assertThat(result.getMaxScore()).isEqualByComparingTo("6");
    }

    @Test
    void oneWrongPlaceOrPairEarnsNothing() {
        Started started = started("T-qt wrong");
        QuestionResponse ordering = started.questions().get(QuestionType.ORDERING);
        Map<String, Long> itemByText = ordering.getOptions().stream().collect(Collectors.toMap(OptionResponse::getOptionText, OptionResponse::getId));
        submit(started, SubmitAnswerRequest.builder().questionId(ordering.getId()).selectedOptionIds(List.of(
                itemByText.get("Günorta"), itemByText.get("Səhər"), itemByText.get("Axşam"), itemByText.get("Gecə"))).build());

        QuestionResponse matching = started.questions().get(QuestionType.MATCHING);
        Map<String, Long> rightByText = matching.getMatchOptions().stream().collect(Collectors.toMap(OptionResponse::getOptionText, OptionResponse::getId));
        Map<String, Long> leftByText = matching.getOptions().stream().collect(Collectors.toMap(OptionResponse::getOptionText, OptionResponse::getId));
        // Two of three right, the third left open
        submit(started, SubmitAnswerRequest.builder().questionId(matching.getId()).matches(List.of(
                new SubmitAnswerRequest.Match(leftByText.get("Azərbaycan"), rightByText.get("Bakı")),
                new SubmitAnswerRequest.Match(leftByText.get("Gürcüstan"), rightByText.get("Tbilisi")))).build());

        QuestionResponse shortAnswer = started.questions().get(QuestionType.SHORT_ANSWER);
        submit(started, SubmitAnswerRequest.builder().questionId(shortAnswer.getId()).textAnswer("Gəncə").build());

        SessionResultResponse result = answers.submitExam(started.sessionId(), started.participant()).block();
        assertThat(result.getCorrectAnswers()).isZero();
        assertThat(result.getIncorrectAnswers()).isEqualTo(3);
        assertThat(result.getTotalScore()).isEqualByComparingTo("0");
    }

    @Test
    void realIdsAnIncompleteOrderAndAReusedRightSideAreRefused() {
        Started started = started("T-qt refused");
        QuestionResponse ordering = started.questions().get(QuestionType.ORDERING);
        List<Long> realIds = stored(started, QuestionType.ORDERING).options().stream()
                .sorted(Comparator.comparingInt(ExamState.OptionState::orderIndex)).map(ExamState.OptionState::id).toList();
        // Guessing with the stored ids does not work
        assertThatThrownBy(() -> submit(started, SubmitAnswerRequest.builder().questionId(ordering.getId()).selectedOptionIds(realIds).build()))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ANSWER_INVALID_OPTION"));
        List<Long> tokens = ordering.getOptions().stream().map(OptionResponse::getId).toList();
        assertThatThrownBy(() -> submit(started, SubmitAnswerRequest.builder().questionId(ordering.getId())
                .selectedOptionIds(tokens.subList(0, 3)).build()))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ANSWER_ORDER_INCOMPLETE"));

        QuestionResponse matching = started.questions().get(QuestionType.MATCHING);
        Long right = matching.getMatchOptions().get(0).getId();
        assertThatThrownBy(() -> submit(started, SubmitAnswerRequest.builder().questionId(matching.getId()).matches(List.of(
                new SubmitAnswerRequest.Match(matching.getOptions().get(0).getId(), right),
                new SubmitAnswerRequest.Match(matching.getOptions().get(1).getId(), right))).build()))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ANSWER_INVALID_OPTION"));
        assertThatThrownBy(() -> submit(started, SubmitAnswerRequest.builder().questionId(matching.getId()).matches(List.of()).build()))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ANSWER_SELECTION_REQUIRED"));
        // Another session's ids mean nothing here
        long foreign = new AnswerTokens("test-secret").token("another-session", stored(started, QuestionType.MATCHING).options().get(0).id());
        assertThatThrownBy(() -> submit(started, SubmitAnswerRequest.builder().questionId(matching.getId()).matches(List.of(
                new SubmitAnswerRequest.Match(matching.getOptions().get(0).getId(), foreign))).build()))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ANSWER_INVALID_OPTION"));
    }

    @Test
    void readinessAndValidationAskForCompleteItemsAndPairs() {
        ExamState.QuestionState ordering = new ExamState.QuestionState(1L, 10L, 0, "q", QuestionType.ORDERING,
                BigDecimal.ONE, false, true, null, List.of(), List.of(new ExamState.OptionState(1L, 0, "only one", null, true, List.of())));
        assertThat(ExamReadinessTestAccess.codes(ordering)).contains("ORDERING_INCOMPLETE");
        ExamState.QuestionState matching = new ExamState.QuestionState(2L, 10L, 1, "q", QuestionType.MATCHING,
                BigDecimal.ONE, false, true, null, List.of(), List.of(
                        new ExamState.OptionState(2L, 0, "A", "1", true, List.of()),
                        new ExamState.OptionState(3L, 1, "B", " ", true, List.of())));
        assertThat(ExamReadinessTestAccess.codes(matching)).contains("MATCHING_INCOMPLETE");
        ExamState.QuestionState shortAnswer = new ExamState.QuestionState(3L, 10L, 2, "q", QuestionType.SHORT_ANSWER,
                BigDecimal.ONE, false, true, null, List.of(), List.of());
        assertThat(ExamReadinessTestAccess.codes(shortAnswer)).contains("SHORT_ANSWER_INCOMPLETE");

        QuestionDTO halfPair = matching();
        halfPair.getOptions().get(2).setMatchText("");
        CreateExamRequest request = ExamBlocksServiceTest.create("T-qt invalid");
        request.setBlocks(List.of(ExamBlockDTO.builder().title("Əsas blok").questions(new ArrayList<>(List.of(halfPair))).build()));
        assertThatThrownBy(() -> service.createExam(request, ExamHistoryTest.nurlan()).block())
                .hasMessageContaining("Both sides are required");
    }
}
