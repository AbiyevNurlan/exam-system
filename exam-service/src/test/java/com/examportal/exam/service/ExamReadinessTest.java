package com.examportal.exam.service;

import com.examportal.common.domain.dto.CreateExamRequest;
import com.examportal.common.domain.dto.ExamReadinessResponse;
import com.examportal.common.domain.dto.ExamResponse;
import com.examportal.common.domain.dto.OptionDTO;
import com.examportal.common.domain.dto.QuestionDTO;
import com.examportal.common.domain.dto.media.QuestionMediaRef;
import com.examportal.common.domain.entity.ExamAccessType;
import com.examportal.common.domain.entity.ExamStatus;
import com.examportal.common.domain.entity.QuestionType;
import com.examportal.common.domain.entity.TimingMode;
import com.examportal.common.exception.ApplicationException;
import com.examportal.exam.structure.ExamState;
import com.examportal.exam.structure.ExamState.BlockState;
import com.examportal.exam.structure.ExamState.MediaRef;
import com.examportal.exam.structure.ExamState.OptionState;
import com.examportal.exam.structure.ExamState.QuestionState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B12a, item 5: every readiness code on its own (no database), and through the services on a real PostgreSQL:
 * GET readiness for ADMIN and the owner, publishing refused with 409 EXAM_NOT_READY and the list, a missing
 * media, block rules before B12b as FEATURE_NOT_AVAILABLE, a plain exam published as before. Docker part skipped
 * without Docker.
 */
class ExamReadinessTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 12, 0);
    private static final UUID OWNER = ExamHistoryTest.nurlan().actorId();
    private static ExamTestStack stack;
    private static ExamService service;

    @BeforeAll
    static void start() {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            stack = ExamTestStack.start();
            service = stack.examService(new ExamFeatures(false, false));
        }
    }

    @AfterAll
    static void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    private static OptionState option(String text, boolean correct, String mediaStatus) {
        return new OptionState(1L, 0, text, correct, mediaStatus == null ? List.of()
                : List.of(new MediaRef(UUID.randomUUID(), 0, null, mediaStatus)));
    }

    private static QuestionState question(long id, QuestionType type, String points, List<OptionState> options) {
        return new QuestionState(id, 10L, 0, "q" + id, type, new BigDecimal(points), false, true, null, List.of(), options);
    }

    private static ExamState exam(TimingMode mode, int duration, LocalDateTime start, LocalDateTime end, ExamAccessType access,
                                  boolean allowReturn, List<BlockState> blocks) {
        return new ExamState(1L, UUID.randomUUID(), "T", null, ExamStatus.DRAFT, duration, start, end, null, false, false, false,
                1, null, null, access, mode, allowReturn, true, true, List.of(), blocks);
    }

    private static BlockState block(long id, Integer limit, boolean free, List<QuestionState> questions) {
        return new BlockState(id, (int) id, "B" + id, null, limit, 0, free, false, true, questions);
    }

    private static List<String> codes(List<ExamReadinessResponse.Item> items) {
        return items.stream().map(ExamReadinessResponse.Item::getCode).toList();
    }

    @Test
    void everyCode() {
        List<OptionState> good = List.of(option("a", true, null), option("b", false, null));
        ExamState ready = exam(TimingMode.EXAM_TIMER, 30, NOW.plusDays(1), NOW.plusDays(1).plusHours(1), ExamAccessType.PUBLIC, false,
                List.of(block(10, null, true, List.of(question(1, QuestionType.MULTIPLE_CHOICE, "1", good)))));
        ExamReadinessResponse fine = ExamReadinessService.evaluate(ready, 0, NOW, false);
        assertThat(fine.isReady()).isTrue();
        assertThat(fine.getBlocking()).isEmpty();
        assertThat(fine.getWarnings()).isEmpty();

        ExamState broken = exam(TimingMode.BLOCK_TIMERS, 30, NOW.minusHours(2), NOW.minusHours(1), ExamAccessType.GROUP, true, List.of(
                block(10, null, false, List.of(
                        question(1, QuestionType.MULTIPLE_CHOICE, "1", List.of(option("a", false, null))),
                        question(2, QuestionType.MULTIPLE_CHOICE, "1", List.of(option("a", false, null), option("b", false, null))),
                        question(3, QuestionType.TRUE_FALSE, "1", List.of(option("t", true, null), option("f", true, null))),
                        question(4, QuestionType.FILL_BLANK, "1", List.of()),
                        question(5, QuestionType.OPEN_ENDED, "0", List.of()),
                        question(6, QuestionType.MULTIPLE_CHOICE, "1", List.of(option("a", true, "DELETED"), option("b", false, null))))),
                block(11, 300, true, List.of())));
        ExamReadinessResponse result = ExamReadinessService.evaluate(broken, 0, NOW, false);
        assertThat(result.isReady()).isFalse();
        assertThat(codes(result.getBlocking())).containsExactlyInAnyOrder("MULTIPLE_CHOICE_INCOMPLETE", "NO_CORRECT_ANSWER",
                "TRUE_FALSE_INCOMPLETE", "FILL_BLANK_INCOMPLETE", "ZERO_POINTS", "MEDIA_MISSING", "EMPTY_BLOCK",
                "BLOCK_TIME_LIMIT_MISSING", "WINDOW_ENDED", "DURATION_EXCEEDS_WINDOW", "FEATURE_NOT_AVAILABLE");
        assertThat(codes(result.getWarnings())).containsExactly("AUDIENCE_EMPTY");
        ExamReadinessResponse.Item tf = result.getBlocking().stream().filter(i -> i.getCode().equals("TRUE_FALSE_INCOMPLETE")).findFirst().orElseThrow();
        assertThat(tf.getQuestionNumber()).isEqualTo(3);
        assertThat(tf.getBlockNumber()).isEqualTo(1);
        assertThat(tf.getMessageKey()).isEqualTo("exam.readiness.TRUE_FALSE_INCOMPLETE");
        // Once B12b is on, block rules are no longer in the way
        assertThat(codes(ExamReadinessService.evaluate(broken, 1, NOW, true).getBlocking())).doesNotContain("FEATURE_NOT_AVAILABLE");

        ExamState noQuestions = exam(TimingMode.EXAM_TIMER, 30, NOW.minusMinutes(5), NOW.plusHours(1), ExamAccessType.PUBLIC, false,
                List.of(block(10, 20, true, List.of())));
        ExamReadinessResponse empty = ExamReadinessService.evaluate(noQuestions, 0, NOW, false);
        assertThat(codes(empty.getBlocking())).containsExactly("NO_QUESTIONS");
        assertThat(codes(empty.getWarnings())).containsExactlyInAnyOrder("START_IN_PAST", "BLOCK_TIME_LIMIT_IGNORED");
    }

    private static String code(Throwable error) {
        return ((ApplicationException) error).getErrorCode();
    }

    @Test
    void publishingRunsTheSameCheck() {
        assumeTrue(stack != null, "Docker is not available");
        CreateExamRequest request = ExamBlocksServiceTest.create("T-b12a readiness");
        QuestionDTO blank = QuestionDTO.builder().questionText("Paytaxt?").questionType(QuestionType.FILL_BLANK)
                .points(BigDecimal.ONE).orderIndex(0).build();
        request.setQuestions(new ArrayList<>(List.of(ExamHistoryTest.question(null, "Bir", 0, "1"), blank)));
        ExamResponse exam = service.createExam(request, ExamHistoryTest.nurlan()).block();

        ExamReadinessResponse readiness = stack.readiness(new ExamFeatures(false, false))
                .check(exam.getId(), OWNER, "INSTRUCTOR", stack.accessPolicy).block();
        assertThat(codes(readiness.getBlocking())).containsExactly("FILL_BLANK_INCOMPLETE");
        assertThat(readiness.getBlocking().get(0).getQuestionNumber()).isEqualTo(2);
        assertThatThrownBy(() -> stack.readiness(new ExamFeatures(false, false))
                .check(exam.getId(), UUID.randomUUID(), "INSTRUCTOR", stack.accessPolicy).block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("EXAM_NOT_OWNER"));

        assertThatThrownBy(() -> service.updateExamStatus(exam.getId(), ExamStatus.ACTIVE, ExamHistoryTest.nurlan()).block())
                .satisfies(e -> {
                    assertThat(code(e)).isEqualTo("EXAM_NOT_READY");
                    Map<?, ?> details = (Map<?, ?>) ((ApplicationException) e).getDetails();
                    assertThat(details.get("blocking").toString()).contains("FILL_BLANK_INCOMPLETE");
                });
        assertThat(stack.scalar("SELECT count(*) FROM exams WHERE status = 'DRAFT' AND id = " + exam.getId())).isEqualTo(1);
        assertThat(stack.scalar("SELECT count(*) FROM exam_published_snapshots WHERE exam_id = " + exam.getId())).isZero();

        // The expected answer added: published
        var fixed = ExamHistoryTest.unchanged(exam);
        fixed.getBlocks().get(0).getQuestions().get(1).setOptions(new ArrayList<>(List.of(
                OptionDTO.builder().optionText("Bakı").isCorrect(true).orderIndex(0).build())));
        service.updateExam(exam.getId(), fixed, ExamHistoryTest.nurlan()).block();
        assertThat(service.updateExamStatus(exam.getId(), ExamStatus.ACTIVE, ExamHistoryTest.nurlan()).block().getStatus())
                .isEqualTo(ExamStatus.ACTIVE);
    }

    @Test
    void aMissingMediaStopsThePublication() {
        assumeTrue(stack != null, "Docker is not available");
        UUID picture = stack.db.sql("INSERT INTO media_assets (owner_id, kind, storage_key, content_type, size_bytes, status) "
                        + "VALUES (:o, 'IMAGE', :k, 'image/png', 10, 'ACTIVE') RETURNING id")
                .bind("o", OWNER).bind("k", UUID.randomUUID().toString()).map(row -> row.get(0, UUID.class)).one().block();
        CreateExamRequest request = ExamBlocksServiceTest.create("T-b12a readiness media");
        QuestionDTO withMedia = ExamHistoryTest.question(null, "Şəkil", 0, "1");
        withMedia.setMedia(List.of(QuestionMediaRef.builder().mediaId(picture).build()));
        request.setQuestions(new ArrayList<>(List.of(withMedia)));
        long id = service.createExam(request, ExamHistoryTest.nurlan()).block().getId();
        // The file went missing (the cleanup removed it, or it failed to be confirmed again)
        stack.db.sql("UPDATE media_assets SET status = 'DELETED' WHERE id = '" + picture + "'").then().block();

        assertThatThrownBy(() -> service.updateExamStatus(id, ExamStatus.ACTIVE, ExamHistoryTest.nurlan()).block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("EXAM_NOT_READY"))
                .satisfies(e -> assertThat(((ApplicationException) e).getDetails().toString()).contains("MEDIA_MISSING"));
    }
}
