package com.examportal.exam.service;

import com.examportal.common.domain.dto.CreateExamRequest;
import com.examportal.common.domain.dto.ExamBlockDTO;
import com.examportal.common.domain.dto.ExamMetaRequest;
import com.examportal.common.domain.dto.ExamResponse;
import com.examportal.common.domain.dto.OptionDTO;
import com.examportal.common.domain.dto.QuestionDTO;
import com.examportal.common.domain.dto.QuestionResponse;
import com.examportal.common.domain.dto.RescheduleExamRequest;
import com.examportal.common.domain.dto.UpdateExamRequest;
import com.examportal.common.domain.dto.group.ExamAudienceRequest;
import com.examportal.common.domain.entity.ExamAccessType;
import com.examportal.common.domain.entity.ExamStatus;
import com.examportal.common.domain.entity.QuestionType;
import com.examportal.common.exception.ApplicationException;
import com.examportal.exam.history.ChangeContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * B12a, item 3, through the services on a real PostgreSQL: every change of an exam leaves history rows in the
 * transaction of the change (create, update of settings, questions, options, answer key, points, order and blocks,
 * title, reschedule, audience, publish, archive, delete and a refused delete); one save shares one saveId; a deleted
 * question is kept whole; changeReason is optional or required by the switch; the table refuses UPDATE, DELETE and
 * TRUNCATE; a history row that cannot be written rolls the change back. Skipped without Docker.
 */
class ExamHistoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
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

    record Row(int revisionNo, UUID saveId, String action, String entityType, Long entityId, JsonNode changes,
               boolean sensitive, String reason, String summaryKey, JsonNode params, String actorName, Integer actorCode,
               String actorRole, String ip) {
    }

    private static List<Row> rows(long examId) {
        return stack.db.sql("""
                        SELECT revision_no, save_id, action, entity_type, entity_id, changes::text AS changes, sensitive, reason,
                               summary_key, summary_params::text AS params, actor_name, actor_code, actor_role, ip
                        FROM exam_revisions WHERE exam_id = :e ORDER BY revision_no
                        """)
                .bind("e", examId)
                .map(row -> {
                    try {
                        return new Row(row.get("revision_no", Integer.class), row.get("save_id", UUID.class),
                                row.get("action", String.class), row.get("entity_type", String.class), row.get("entity_id", Long.class),
                                JSON.readTree(row.get("changes", String.class)), Boolean.TRUE.equals(row.get("sensitive", Boolean.class)),
                                row.get("reason", String.class), row.get("summary_key", String.class),
                                JSON.readTree(row.get("params", String.class)), row.get("actor_name", String.class),
                                row.get("actor_code", Integer.class), row.get("actor_role", String.class), row.get("ip", String.class));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .all().collectList().block();
    }

    private static List<Row> since(long examId, int afterRevision) {
        return rows(examId).stream().filter(row -> row.revisionNo() > afterRevision).toList();
    }

    private static int last(long examId) {
        List<Row> all = rows(examId);
        return all.isEmpty() ? 0 : all.get(all.size() - 1).revisionNo();
    }

    static ChangeContext nurlan() {
        return new ChangeContext(OWNER, "INSTRUCTOR", "Nurlan Abiyev", 1234567, "10.0.0.7", "JUnit", "corr-1", null, UUID.randomUUID());
    }

    static QuestionDTO question(Long id, String text, int correct, String points) {
        List<OptionDTO> options = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            options.add(OptionDTO.builder().optionText("option " + (char) ('A' + i)).isCorrect(i == correct).orderIndex(i).build());
        }
        return QuestionDTO.builder().id(id).questionText(text).questionType(QuestionType.MULTIPLE_CHOICE)
                .points(new BigDecimal(points)).orderIndex(0).allowMultipleAnswers(false).isRequired(true).options(options).build();
    }

    /**
     * The request that saves the exam exactly as it is, with every id
     */
    static UpdateExamRequest unchanged(ExamResponse exam) {
        UpdateExamRequest request = ExamBlocksServiceTest.update(exam);
        List<ExamBlockDTO> blocks = new ArrayList<>();
        for (var block : exam.getBlocks()) {
            List<QuestionDTO> questions = new ArrayList<>();
            for (Long id : block.getQuestionIds()) {
                QuestionResponse q = exam.getQuestions().stream().filter(x -> x.getId().equals(id)).findFirst().orElseThrow();
                List<OptionDTO> options = new ArrayList<>();
                q.getOptions().forEach(o -> options.add(OptionDTO.builder().id(o.getId()).optionText(o.getOptionText())
                        .isCorrect(o.getIsCorrect()).orderIndex(o.getOrderIndex()).build()));
                questions.add(QuestionDTO.builder().id(q.getId()).questionText(q.getQuestionText()).questionType(q.getQuestionType())
                        .points(q.getPoints()).orderIndex(0).allowMultipleAnswers(q.getAllowMultipleAnswers())
                        .isRequired(q.getIsRequired()).explanation(q.getExplanation()).options(options).build());
            }
            blocks.add(ExamBlockDTO.builder().id(block.getId()).title(block.getTitle()).instructions(block.getInstructions())
                    .timeLimitMinutes(block.getTimeLimitMinutes()).breakAfterMinutes(block.getBreakAfterMinutes())
                    .freeNavigation(block.getFreeNavigation()).shuffleQuestions(block.getShuffleQuestions())
                    .showResultToParticipant(block.getShowResultToParticipant()).questions(questions).build());
        }
        request.setBlocks(blocks);
        return request;
    }

    private static ExamResponse sevenQuestions(String title) {
        CreateExamRequest request = ExamBlocksServiceTest.create(title);
        List<QuestionDTO> questions = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            questions.add(question(null, "Sual " + i + ": nə qədərdir?", 1, "1"));
        }
        request.setQuestions(questions);
        return service.createExam(request, nurlan()).block();
    }

    private static String code(Throwable error) {
        return ((ApplicationException) error).getErrorCode();
    }

    @Test
    void createAndOneSaveEditingTheFifthAndSeventhQuestion() {
        ExamResponse exam = sevenQuestions("T-b12a history save");
        List<Row> created = rows(exam.getId());
        assertThat(created).hasSize(1);
        Row first = created.get(0);
        assertThat(first.action()).isEqualTo("EXAM_CREATED");
        assertThat(first.revisionNo()).isEqualTo(1);
        assertThat(first.actorName()).isEqualTo("Nurlan Abiyev");
        assertThat(first.actorCode()).isEqualTo(1234567);
        assertThat(first.actorRole()).isEqualTo("INSTRUCTOR");
        assertThat(first.ip()).isEqualTo("10.0.0.7");
        assertThat(first.params().get("questionCount").asInt()).isEqualTo(7);

        // Question 5: correct answer B -> C and points 1 -> 2; question 7: its text
        UpdateExamRequest save = unchanged(exam);
        QuestionDTO fifth = save.getBlocks().get(0).getQuestions().get(4);
        fifth.getOptions().forEach(o -> o.setIsCorrect(false));
        fifth.getOptions().get(2).setIsCorrect(true);
        fifth.setPoints(new BigDecimal("2"));
        save.getBlocks().get(0).getQuestions().get(6).setQuestionText("Sual 7: dəqiq nə qədərdir?");
        service.updateExam(exam.getId(), save, nurlan()).block();

        List<Row> rows = since(exam.getId(), 1);
        assertThat(rows).extracting(Row::action).containsExactly("EXAM_UPDATED", "QUESTION_UPDATED", "QUESTION_UPDATED");
        assertThat(rows).extracting(Row::saveId).containsOnly(rows.get(0).saveId());
        assertThat(rows).extracting(Row::revisionNo).containsExactly(2, 3, 4);
        assertThat(rows.get(0).summaryKey()).isEqualTo("EXAM_SAVED");
        assertThat(rows.get(0).params().get("questionNumbers").toString()).isEqualTo("[5,7]");
        assertThat(rows.get(0).sensitive()).isTrue();

        Row q5 = rows.get(1);
        assertThat(q5.entityId()).isEqualTo(exam.getQuestions().get(4).getId());
        assertThat(q5.summaryKey()).isEqualTo("QUESTION_EDITED");
        assertThat(q5.sensitive()).isTrue();
        assertThat(q5.params().get("questionNumber").asInt()).isEqualTo(5);
        assertThat(q5.params().get("correctBefore").asText()).isEqualTo("B");
        assertThat(q5.params().get("correctAfter").asText()).isEqualTo("C");
        assertThat(q5.params().get("pointsBefore").decimalValue()).isEqualByComparingTo("1");
        assertThat(q5.params().get("pointsAfter").decimalValue()).isEqualByComparingTo("2");
        assertThat(q5.params().get("actorName").asText()).isEqualTo("Nurlan Abiyev");
        assertThat(q5.params().get("actorCode").asInt()).isEqualTo(1234567);
        assertThat(q5.params().get("blockTitle").asText()).isEqualTo("Əsas blok");
        assertThat(q5.params().get("questionPreview").asText()).isEqualTo("Sual 5: nə qədərdir?");
        assertThat(q5.params().get("changedFields").toString()).isEqualTo("[\"points\",\"correctAnswer\"]");
        assertThat(q5.changes().get("correctAnswer").get(0).get(0).get("label").asText()).isEqualTo("B");
        assertThat(q5.changes().get("correctAnswer").get(1).get(0).get("label").asText()).isEqualTo("C");

        Row q7 = rows.get(2);
        assertThat(q7.params().get("questionNumber").asInt()).isEqualTo(7);
        assertThat(q7.sensitive()).isFalse();
        assertThat(q7.changes().get("questionText").get(1).asText()).isEqualTo("Sual 7: dəqiq nə qədərdir?");

        // Only the answer key: one kind of change, so the action names it
        ExamResponse now = service.getExamById(exam.getId(), OWNER, "INSTRUCTOR").block();
        UpdateExamRequest keyOnly = unchanged(now);
        keyOnly.getBlocks().get(0).getQuestions().get(0).getOptions().forEach(o -> o.setIsCorrect(false));
        keyOnly.getBlocks().get(0).getQuestions().get(0).getOptions().get(3).setIsCorrect(true);
        int before = last(exam.getId());
        service.updateExam(exam.getId(), keyOnly, nurlan()).block();
        assertThat(since(exam.getId(), before)).extracting(Row::action).containsExactly("EXAM_UPDATED", "CORRECT_ANSWER_CHANGED");

        // A save that changes nothing writes nothing
        before = last(exam.getId());
        service.updateExam(exam.getId(), unchanged(service.getExamById(exam.getId(), OWNER, "INSTRUCTOR").block()), nurlan()).block();
        assertThat(last(exam.getId())).isEqualTo(before);
    }

    @Test
    void deletedQuestionsAreKeptWholeAndOrderAndBlockMovesAreNamed() {
        ExamResponse exam = sevenQuestions("T-b12a history order");
        // Delete question 3, move question 7 to the top
        UpdateExamRequest save = unchanged(exam);
        List<QuestionDTO> questions = save.getBlocks().get(0).getQuestions();
        QuestionDTO third = questions.remove(2);
        QuestionDTO seventh = questions.remove(questions.size() - 1);
        questions.add(0, seventh);
        service.updateExam(exam.getId(), save, nurlan()).block();

        List<Row> rows = since(exam.getId(), 1);
        Row deleted = rows.stream().filter(r -> r.action().equals("QUESTION_DELETED")).findFirst().orElseThrow();
        JsonNode whole = deleted.changes().get("question").get(0);
        assertThat(whole.get("text").asText()).isEqualTo("Sual 3: nə qədərdir?");
        assertThat(whole.get("options")).hasSize(4);
        assertThat(whole.get("options").get(1).get("correct").asBoolean()).isTrue();
        assertThat(whole.get("options").get(1).get("label").asText()).isEqualTo("B");
        assertThat(deleted.params().get("questionNumber").asInt()).isEqualTo(3);
        assertThat(deleted.entityId()).isEqualTo(third.getId());

        // Only the question that really moved is reported, with its number before and after
        List<Row> reordered = rows.stream().filter(r -> r.action().equals("QUESTION_REORDERED")).toList();
        assertThat(reordered).hasSize(1);
        assertThat(reordered.get(0).entityId()).isEqualTo(seventh.getId());
        assertThat(reordered.get(0).changes().get("questionNumber").toString()).isEqualTo("[7,1]");
        assertThat(reordered.get(0).params().get("questionNumberBefore").asInt()).isEqualTo(7);

        // A second block, and question 1 moves into it
        ExamResponse now = service.getExamById(exam.getId(), OWNER, "INSTRUCTOR").block();
        UpdateExamRequest twoBlocks = unchanged(now);
        QuestionDTO moving = twoBlocks.getBlocks().get(0).getQuestions().remove(0);
        twoBlocks.setBlocks(new ArrayList<>(twoBlocks.getBlocks()));
        twoBlocks.getBlocks().add(ExamBlockDTO.builder().title("Məntiq").questions(new ArrayList<>(List.of(moving))).build());
        int before = last(exam.getId());
        service.updateExam(exam.getId(), twoBlocks, nurlan()).block();
        List<Row> blockRows = since(exam.getId(), before);
        assertThat(blockRows).extracting(Row::action).contains("BLOCK_ADDED", "QUESTION_MOVED");
        Row moved = blockRows.stream().filter(r -> r.action().equals("QUESTION_MOVED")).findFirst().orElseThrow();
        assertThat(moved.changes().get("block").get(0).get("title").asText()).isEqualTo("Əsas blok");
        assertThat(moved.changes().get("block").get(1).get("title").asText()).isEqualTo("Məntiq");
        assertThat(moved.params().get("blockNumber").asInt()).isEqualTo(2);
    }

    @Test
    void everyOtherPathLeavesItsRow() {
        ExamResponse exam = sevenQuestions("T-b12a history paths");
        long id = exam.getId();

        ExamMetaRequest meta = new ExamMetaRequest();
        meta.setTitle("T-b12a history paths renamed");
        stack.metaService(new ExamFeatures(false, false)).updateMeta(id, meta, nurlan()).block();
        assertThat(since(id, 1)).extracting(Row::action).containsExactly("META_CHANGED");

        ExamAudienceRequest audience = new ExamAudienceRequest();
        audience.setAccessType(ExamAccessType.GROUP);
        long group = stack.db.sql("INSERT INTO user_groups (name, created_by, owner_id) VALUES ('T-b12a group', :o, :o) RETURNING id")
                .bind("o", OWNER).map(row -> row.get(0, Long.class)).one().block();
        audience.setGroupIds(List.of(group));
        service.setAudience(id, audience, nurlan()).block();
        assertThat(rows(id).get(rows(id).size() - 1).action()).isEqualTo("AUDIENCE_CHANGED");

        service.updateExamStatus(id, ExamStatus.ACTIVE, nurlan()).block();
        Row published = rows(id).get(rows(id).size() - 1);
        assertThat(published.action()).isEqualTo("PUBLISHED");
        assertThat(published.sensitive()).isTrue();

        RescheduleExamRequest later = new RescheduleExamRequest();
        later.setEndTime(LocalDateTime.now().plusDays(5).truncatedTo(ChronoUnit.MINUTES));
        service.rescheduleExam(id, later, nurlan()).block();
        Row rescheduled = rows(id).get(rows(id).size() - 1);
        assertThat(rescheduled.action()).isEqualTo("RESCHEDULED");
        assertThat(rescheduled.sensitive()).isTrue();
        assertThat(rescheduled.changes().has("endTime")).isTrue();

        service.updateExamStatus(id, ExamStatus.ARCHIVED, nurlan()).block();
        assertThat(rows(id).get(rows(id).size() - 1).action()).isEqualTo("ARCHIVED");

        // Revision numbers run without gaps
        List<Row> all = rows(id);
        for (int i = 0; i < all.size(); i++) {
            assertThat(all.get(i).revisionNo()).isEqualTo(i + 1);
        }
    }

    @Test
    void aDeleteIsRecordedAndTheHistoryOutlivesTheExam() {
        ExamResponse kept = sevenQuestions("T-b12a history refused");
        stack.db.sql("INSERT INTO exam_sessions (session_id, exam_id, user_id, status) VALUES (:s, :e, :u, 'COMPLETED')")
                .bind("s", UUID.randomUUID().toString()).bind("e", kept.getId()).bind("u", UUID.randomUUID()).then().block();
        assertThatThrownBy(() -> service.deleteExam(kept.getId(), nurlan()).block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("EXAM_CANNOT_DELETE"));
        Row refused = rows(kept.getId()).get(rows(kept.getId()).size() - 1);
        assertThat(refused.action()).isEqualTo("DELETE_ATTEMPTED");
        assertThat(refused.params().get("outcome").asText()).isEqualTo("REFUSED_HAS_SESSIONS");
        assertThat(stack.scalar("SELECT count(*) FROM exams WHERE id = " + kept.getId())).isEqualTo(1);

        ExamResponse gone = sevenQuestions("T-b12a history deleted");
        service.deleteExam(gone.getId(), nurlan()).block();
        assertThat(stack.scalar("SELECT count(*) FROM exams WHERE id = " + gone.getId())).isZero();
        List<Row> left = rows(gone.getId());
        assertThat(left).extracting(Row::action).containsExactly("EXAM_CREATED", "DELETE_ATTEMPTED");
        JsonNode whole = left.get(1).changes().get("exam").get(0);
        assertThat(whole.get("blocks").get(0).get("questions")).hasSize(7);
        assertThat(left.get(1).summaryKey()).isEqualTo("EXAM_DELETED");
    }

    @Test
    void theReasonIsOptionalUnlessTheSwitchIsOn() {
        ExamResponse exam = sevenQuestions("T-b12a history reason");
        long id = exam.getId();
        // Published and waiting for its start (SCHEDULED): still editable while nobody has started
        stack.db.sql("UPDATE exams SET status = 'SCHEDULED' WHERE id = " + id).then().block();
        stack.cache.invalidateExamCache(id).block();

        // Off (the default): a sensitive change needs no reason
        UpdateExamRequest points = unchanged(service.getExamById(id, OWNER, "INSTRUCTOR").block());
        points.getBlocks().get(0).getQuestions().get(0).setPoints(new BigDecimal("3"));
        service.updateExam(id, points, nurlan()).block();
        assertThat(rows(id).get(rows(id).size() - 1).reason()).isNull();

        ExamService strict = stack.examService(new ExamFeatures(false, true));
        UpdateExamRequest more = unchanged(service.getExamById(id, OWNER, "INSTRUCTOR").block());
        more.getBlocks().get(0).getQuestions().get(0).setPoints(new BigDecimal("4"));
        int before = last(id);
        assertThatThrownBy(() -> strict.updateExam(id, more, nurlan()).block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("CHANGE_REASON_REQUIRED"));
        // Nothing changed, nothing written
        assertThat(last(id)).isEqualTo(before);
        assertThat(stack.scalar("SELECT points FROM questions WHERE id = " + exam.getQuestions().get(0).getId())).isEqualTo(3);

        UpdateExamRequest withReason = unchanged(service.getExamById(id, OWNER, "INSTRUCTOR").block());
        withReason.getBlocks().get(0).getQuestions().get(0).setPoints(new BigDecimal("4"));
        strict.updateExam(id, withReason, nurlan().withReason("Açar səhv idi, düzəldildi")).block();
        List<Row> reasoned = since(id, before);
        assertThat(reasoned).isNotEmpty().allSatisfy(row -> assertThat(row.reason()).isEqualTo("Açar səhv idi, düzəldildi"));

        // Not sensitive (a question text): no reason needed even with the switch on
        UpdateExamRequest text = unchanged(service.getExamById(id, OWNER, "INSTRUCTOR").block());
        text.getBlocks().get(0).getQuestions().get(1).setQuestionText("Sual 2 yenidən yazıldı");
        strict.updateExam(id, text, nurlan()).block();

        // A draft never needs one
        ExamResponse draft = sevenQuestions("T-b12a history reason draft");
        UpdateExamRequest draftPoints = unchanged(draft);
        draftPoints.getBlocks().get(0).getQuestions().get(0).setPoints(new BigDecimal("5"));
        strict.updateExam(draft.getId(), draftPoints, nurlan()).block();

        // Archiving an ACTIVE exam is sensitive
        stack.db.sql("UPDATE exams SET status = 'ACTIVE' WHERE id = " + id).then().block();
        assertThatThrownBy(() -> strict.updateExamStatus(id, ExamStatus.ARCHIVED, nurlan()).block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("CHANGE_REASON_REQUIRED"));
        assertThat(stack.scalar("SELECT count(*) FROM exams WHERE status = 'ACTIVE' AND id = " + id)).isEqualTo(1);
        strict.updateExamStatus(id, ExamStatus.ARCHIVED, nurlan().withReason("Tədbir ləğv olundu")).block();
        assertThat(stack.scalar("SELECT count(*) FROM exams WHERE status = 'ARCHIVED' AND id = " + id)).isEqualTo(1);
    }

    @Test
    void theHistoryIsAppendOnlyAndPartOfTheChangesTransaction() {
        ExamResponse exam = sevenQuestions("T-b12a history guard");
        long id = exam.getId();
        // A row trigger needs a row: one snapshot to try on
        stack.db.sql("INSERT INTO exam_published_snapshots (exam_id, version_no, snapshot, sha256, actor_user_id) "
                + "VALUES (" + id + ", 1, '{}', repeat('a', 64), '" + OWNER + "')").then().block();
        for (String sql : List.of("UPDATE exam_revisions SET reason = 'x' WHERE exam_id = " + id,
                "DELETE FROM exam_revisions WHERE exam_id = " + id,
                "TRUNCATE exam_revisions",
                "UPDATE exam_published_snapshots SET sha256 = sha256",
                "DELETE FROM exam_published_snapshots",
                "TRUNCATE exam_published_snapshots")) {
            assertThatThrownBy(() -> stack.db.sql(sql).then().block()).as(sql).hasMessageContaining("append-only");
        }
        assertThat(rows(id)).hasSize(1);

        // A history row the database refuses (a reason of 2 characters gets past no check here): the change is rolled back
        UpdateExamRequest save = unchanged(exam);
        save.getBlocks().get(0).getQuestions().get(0).setQuestionText("never stored");
        assertThatThrownBy(() -> service.updateExam(id, save, nurlan().withReason("ab")).block())
                .hasMessageContaining("ck_exam_revisions_reason");
        assertThat(stack.db.sql("SELECT question_text FROM questions WHERE id = " + exam.getQuestions().get(0).getId())
                .map(row -> row.get(0, String.class)).one().block()).isEqualTo("Sual 1: nə qədərdir?");
        assertThat(rows(id)).hasSize(1);
    }
}
