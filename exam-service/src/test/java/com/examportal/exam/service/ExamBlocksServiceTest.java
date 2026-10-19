package com.examportal.exam.service;

import com.examportal.common.domain.dto.CreateExamRequest;
import com.examportal.common.domain.dto.ExamBlockDTO;
import com.examportal.common.domain.dto.ExamResponse;
import com.examportal.common.domain.dto.OptionDTO;
import com.examportal.common.domain.dto.QuestionDTO;
import com.examportal.common.domain.dto.UpdateExamRequest;
import com.examportal.common.domain.entity.ExamStatus;
import com.examportal.common.domain.entity.QuestionType;
import com.examportal.common.domain.entity.TimingMode;
import com.examportal.common.exception.ApplicationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B12a, item 1 through ExamService on a real PostgreSQL: the older request format (questions without
 * blocks) is one block "Əsas blok"; blocks in requests and responses; validation of blocks and block
 * timers (EXAM_DURATION_EXCEEDS_WINDOW, totalMinutes); the publish gate FEATURE_NOT_AVAILABLE for block
 * timers, fixed navigation and returning to earlier blocks unless the switch is on; BLOCKS_REQUIRED.
 * Skipped without Docker.
 */
class ExamBlocksServiceTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static ExamTestStack stack;
    private static ExamService service;

    @BeforeAll
    static void start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        stack = ExamTestStack.start();
        service = stack.examService(new ExamFeatures(false, false));
    }

    @AfterAll
    static void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    static QuestionDTO mc(String text) {
        return QuestionDTO.builder().questionText(text).questionType(QuestionType.MULTIPLE_CHOICE).points(BigDecimal.ONE)
                .orderIndex(0).allowMultipleAnswers(false).isRequired(true)
                .options(new ArrayList<>(List.of(OptionDTO.builder().optionText("right").isCorrect(true).orderIndex(0).build(),
                        OptionDTO.builder().optionText("wrong").isCorrect(false).orderIndex(1).build())))
                .build();
    }

    static CreateExamRequest create(String title) {
        LocalDateTime start = LocalDateTime.now().plusDays(1).truncatedTo(ChronoUnit.MINUTES);
        return CreateExamRequest.builder().title(title).durationMinutes(30).startTime(start).endTime(start.plusHours(2))
                .shuffleQuestions(false).shuffleOptions(false).showResultsImmediately(true).maxAttempts(1).build();
    }

    static UpdateExamRequest update(ExamResponse exam) {
        UpdateExamRequest request = new UpdateExamRequest();
        request.setId(exam.getId());
        request.setTitle(exam.getTitle());
        request.setDurationMinutes(exam.getDurationMinutes());
        request.setStartTime(exam.getStartTime());
        request.setEndTime(exam.getEndTime());
        request.setShuffleQuestions(false);
        request.setShuffleOptions(false);
        request.setShowResultsImmediately(true);
        request.setMaxAttempts(1);
        return request;
    }

    private static String code(Throwable error) {
        return ((ApplicationException) error).getErrorCode();
    }

    @Test
    void theOlderFormatIsOneMainBlock() {
        CreateExamRequest request = create("T-b12a legacy");
        request.setQuestions(new ArrayList<>(List.of(mc("one"), mc("two"))));
        ExamResponse created = service.createExam(request, OWNER, "INSTRUCTOR").block();

        assertThat(created.getBlocks()).hasSize(1);
        assertThat(created.getBlocks().get(0).getTitle()).isEqualTo("Əsas blok");
        assertThat(created.getBlocks().get(0).getFreeNavigation()).isTrue();
        assertThat(created.getBlocks().get(0).getQuestionIds()).hasSize(2);
        assertThat(created.getQuestions()).allSatisfy(q -> assertThat(q.getBlockId()).isEqualTo(created.getBlocks().get(0).getId()));
        assertThat(created.getTimingMode()).isEqualTo(TimingMode.EXAM_TIMER);
        assertThat(created.getTotalMinutes()).isEqualTo(30);
        assertThat(stack.scalar("SELECT count(*) FROM exam_blocks WHERE exam_id = " + created.getId())).isEqualTo(1);

        // An update in the older format keeps the block, its id and its settings, and the question ids
        UpdateExamRequest change = update(created);
        QuestionDTO first = mc("one changed");
        first.setId(created.getQuestions().get(0).getId());
        change.setQuestions(new ArrayList<>(List.of(first, mc("three"))));
        ExamResponse updated = service.updateExam(created.getId(), change, OWNER, "INSTRUCTOR").block();
        assertThat(updated.getBlocks()).extracting(b -> b.getId()).containsExactly(created.getBlocks().get(0).getId());
        assertThat(updated.getQuestions().get(0).getId()).isEqualTo(created.getQuestions().get(0).getId());
        assertThat(updated.getQuestions()).extracting(q -> q.getQuestionText()).containsExactly("one changed", "three");
    }

    @Test
    void blocksInRequestsAndResponses() {
        CreateExamRequest request = create("T-b12a blocks");
        request.setBlocks(List.of(
                ExamBlockDTO.builder().title("Riyaziyyat").instructions("Kalkulyator olmadan").questions(new ArrayList<>(List.of(mc("m1"), mc("m2")))).build(),
                ExamBlockDTO.builder().title("Məntiq").showResultToParticipant(false).questions(new ArrayList<>(List.of(mc("l1")))).build()));
        ExamResponse created = service.createExam(request, OWNER, "INSTRUCTOR").block();

        assertThat(created.getBlocks()).extracting(b -> b.getTitle()).containsExactly("Riyaziyyat", "Məntiq");
        assertThat(created.getBlocks()).extracting(b -> b.getPosition()).containsExactly(1, 2);
        assertThat(created.getBlocks().get(1).getShowResultToParticipant()).isFalse();
        assertThat(created.getQuestions()).extracting(q -> q.getQuestionText()).containsExactly("m1", "m2", "l1");
        assertThat(created.getTotalQuestions()).isEqualTo(3);

        // The participant view (published, PUBLIC) shows the blocks, never the answer key
        stack.db.sql("UPDATE exams SET status = 'ACTIVE', start_time = LOCALTIMESTAMP - interval '1 minute' WHERE id = "
                + created.getId()).then().block();
        stack.cache.invalidateExamCache(created.getId()).block();
        ExamResponse participant = service.getExamById(created.getId(), UUID.randomUUID(), "STUDENT").block();
        assertThat(participant.getBlocks()).hasSize(2);
        assertThat(participant.getQuestions()).allSatisfy(q -> q.getOptions().forEach(o -> assertThat(o.getIsCorrect()).isNull()));

        // Both formats at once are refused; an exam with two blocks needs blocks
        stack.db.sql("UPDATE exams SET status = 'DRAFT' WHERE id = " + created.getId()).then().block();
        UpdateExamRequest both = update(created);
        both.setQuestions(new ArrayList<>(List.of(mc("x"))));
        both.setBlocks(List.of(ExamBlockDTO.builder().questions(new ArrayList<>(List.of(mc("y")))).build()));
        assertThatThrownBy(() -> service.updateExam(created.getId(), both, OWNER, "INSTRUCTOR").block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_ERROR"));
        UpdateExamRequest flat = update(created);
        flat.setQuestions(new ArrayList<>(List.of(mc("x"))));
        assertThatThrownBy(() -> service.updateExam(created.getId(), flat, OWNER, "INSTRUCTOR").block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("BLOCKS_REQUIRED"));
        // Positions out of order
        UpdateExamRequest badPositions = update(created);
        badPositions.setBlocks(List.of(ExamBlockDTO.builder().position(2).questions(new ArrayList<>(List.of(mc("y")))).build()));
        assertThatThrownBy(() -> service.updateExam(created.getId(), badPositions, OWNER, "INSTRUCTOR").block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_ERROR"));
        assertThat(stack.scalar("SELECT count(*) FROM questions WHERE exam_id = " + created.getId())).isEqualTo(3);
    }

    @Test
    void blockTimersAddUpAndMustFitTheWindow() {
        CreateExamRequest request = create("T-b12a timers");
        request.setTimingMode(TimingMode.BLOCK_TIMERS);
        request.setBlocks(List.of(
                ExamBlockDTO.builder().title("A").timeLimitMinutes(20).breakAfterMinutes(5).questions(new ArrayList<>(List.of(mc("a")))).build(),
                ExamBlockDTO.builder().title("B").timeLimitMinutes(30).breakAfterMinutes(15).questions(new ArrayList<>(List.of(mc("b")))).build()));
        ExamResponse created = service.createExam(request, OWNER, "INSTRUCTOR").block();
        // 20 + 5 + 30; the break after the last block does not count (and is stored as 0)
        assertThat(created.getTotalMinutes()).isEqualTo(55);
        assertThat(created.getDurationMinutes()).isEqualTo(55);
        assertThat(created.getBlocks().get(1).getBreakAfterMinutes()).isZero();

        CreateExamRequest tooLong = create("T-b12a too long");
        tooLong.setTimingMode(TimingMode.BLOCK_TIMERS);
        tooLong.setEndTime(tooLong.getStartTime().plusMinutes(50));
        tooLong.setBlocks(request.getBlocks());
        assertThatThrownBy(() -> service.createExam(tooLong, OWNER, "INSTRUCTOR").block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("EXAM_DURATION_EXCEEDS_WINDOW"));

        CreateExamRequest missing = create("T-b12a no limit");
        missing.setTimingMode(TimingMode.BLOCK_TIMERS);
        missing.setBlocks(List.of(ExamBlockDTO.builder().title("A").questions(new ArrayList<>(List.of(mc("a")))).build()));
        assertThatThrownBy(() -> service.createExam(missing, OWNER, "INSTRUCTOR").block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_ERROR"))
                .hasMessageContaining("time limit");
        assertThat(stack.scalar("SELECT count(*) FROM exams WHERE title IN ('T-b12a too long', 'T-b12a no limit')")).isZero();
    }

    @Test
    void publishingWhatNeedsBlockRulesWaitsForB12b() {
        CreateExamRequest timers = create("T-b12a gate timers");
        timers.setTimingMode(TimingMode.BLOCK_TIMERS);
        timers.setBlocks(List.of(ExamBlockDTO.builder().title("A").timeLimitMinutes(20).questions(new ArrayList<>(List.of(mc("a")))).build()));
        CreateExamRequest fixed = create("T-b12a gate navigation");
        fixed.setBlocks(List.of(ExamBlockDTO.builder().title("A").freeNavigation(false).questions(new ArrayList<>(List.of(mc("a")))).build()));
        CreateExamRequest back = create("T-b12a gate return");
        back.setAllowReturnToPreviousBlocks(true);
        back.setQuestions(new ArrayList<>(List.of(mc("a"))));
        CreateExamRequest plain = create("T-b12a gate plain");
        plain.setBlocks(List.of(ExamBlockDTO.builder().title("A").questions(new ArrayList<>(List.of(mc("a")))).build(),
                ExamBlockDTO.builder().title("B").questions(new ArrayList<>(List.of(mc("b")))).build()));

        for (CreateExamRequest request : List.of(timers, fixed, back)) {
            Long id = service.createExam(request, OWNER, "INSTRUCTOR").block().getId();
            assertThatThrownBy(() -> service.updateExamStatus(id, ExamStatus.ACTIVE, OWNER, "INSTRUCTOR").block())
                    .as(request.getTitle())
                    .satisfies(e -> assertThat(code(e)).isEqualTo("FEATURE_NOT_AVAILABLE"));
            assertThat(stack.scalar("SELECT count(*) FROM exams WHERE status = 'DRAFT' AND id = " + id)).isEqualTo(1);
            // Once B12b switches the rules on, the same exam can be published
            ExamResponse published = stack.examService(new ExamFeatures(true, false))
                    .updateExamStatus(id, ExamStatus.ACTIVE, OWNER, "INSTRUCTOR").block();
            assertThat(published.getStatus()).isEqualTo(ExamStatus.ACTIVE);
        }
        // Two plain blocks with an exam timer: published as before
        Long plainId = service.createExam(plain, OWNER, "INSTRUCTOR").block().getId();
        assertThat(service.updateExamStatus(plainId, ExamStatus.ACTIVE, OWNER, "INSTRUCTOR").block().getStatus())
                .isEqualTo(ExamStatus.ACTIVE);

        // A published (SCHEDULED) exam cannot be switched to block timers by an update either
        stack.db.sql("UPDATE exams SET status = 'SCHEDULED' WHERE id = " + plainId).then().block();
        ExamResponse scheduled = service.getExamById(plainId, OWNER, "INSTRUCTOR").block();
        UpdateExamRequest toTimers = update(scheduled);
        toTimers.setTimingMode(TimingMode.BLOCK_TIMERS);
        toTimers.setBlocks(List.of(ExamBlockDTO.builder().id(scheduled.getBlocks().get(0).getId()).title("A").timeLimitMinutes(10)
                .questions(new ArrayList<>(List.of(mc("a")))).build()));
        assertThatThrownBy(() -> service.updateExam(plainId, toTimers, OWNER, "INSTRUCTOR").block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("FEATURE_NOT_AVAILABLE"));
        assertThat(stack.scalar("SELECT count(*) FROM exams WHERE timing_mode = 'EXAM_TIMER' AND id = " + plainId)).isEqualTo(1);
        assertThat(stack.scalar("SELECT count(*) FROM exam_blocks WHERE exam_id = " + plainId)).isEqualTo(2);
    }
}
