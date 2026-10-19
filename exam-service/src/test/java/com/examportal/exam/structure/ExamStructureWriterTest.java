package com.examportal.exam.structure;

import com.examportal.common.domain.dto.ExamBlockDTO;
import com.examportal.common.domain.dto.OptionDTO;
import com.examportal.common.domain.dto.QuestionDTO;
import com.examportal.common.domain.dto.media.QuestionMediaRef;
import com.examportal.common.domain.entity.QuestionType;
import com.examportal.common.exception.ApplicationException;
import com.examportal.exam.repository.MediaLinkRepository;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B12a, item 2, on a real PostgreSQL with all migrations: an update changes questions, options and blocks
 * IN PLACE. Ids of kept rows stay, new rows are created, rows left out are deleted; a question moves to
 * another block; blocks swap places; media links of kept questions and options stay; an unchanged
 * question is not written at all. Skipped without Docker.
 */
class ExamStructureWriterTest {

    private static PostgreSQLContainer<?> postgres;
    private static DatabaseClient db;
    private static TransactionalOperator tx;
    private static ExamStateRepository states;
    private static ExamStructureWriter writer;
    private static final StructurePlanner PLANNER = new StructurePlanner();

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
        states = new ExamStateRepository(db);
        writer = new ExamStructureWriter(db, new MediaLinkRepository(db));
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static long exam() {
        return db.sql("INSERT INTO exams (title, start_time, end_time, status, created_by) "
                        + "VALUES ('T-b12a writer', LOCALTIMESTAMP + interval '1 day', LOCALTIMESTAMP + interval '2 day', 'DRAFT', :o) RETURNING id")
                .bind("o", UUID.randomUUID()).map(row -> row.get(0, Long.class)).one().block();
    }

    private static UUID image() {
        return db.sql("INSERT INTO media_assets (owner_id, kind, storage_key, content_type, size_bytes, status) "
                        + "VALUES (:o, 'IMAGE', :k, 'image/png', 10, 'ACTIVE') RETURNING id")
                .bind("o", UUID.randomUUID()).bind("k", UUID.randomUUID().toString())
                .map(row -> row.get(0, UUID.class)).one().block();
    }

    private static OptionDTO option(Long id, String text, boolean correct) {
        return OptionDTO.builder().id(id).optionText(text).isCorrect(correct).orderIndex(0).build();
    }

    private static QuestionDTO mc(Long id, String text, List<OptionDTO> options) {
        return QuestionDTO.builder().id(id).questionText(text).questionType(QuestionType.MULTIPLE_CHOICE)
                .points(BigDecimal.ONE).orderIndex(0).allowMultipleAnswers(false).isRequired(true)
                .options(new ArrayList<>(options)).build();
    }

    private static ExamState load(long examId) {
        return states.load(examId).block();
    }

    private static void save(long examId, StructurePlan plan) {
        tx.transactional(states.load(examId).flatMap(current -> writer.apply(examId, current, plan))).block();
    }

    private static long scalar(String sql) {
        return db.sql(sql).map(row -> ((Number) row.get(0)).longValue()).one().block();
    }

    @Test
    void questionsAndOptionsKeepTheirIdsAndOnlyWhatIsLeftOutGoes() {
        long examId = exam();
        UUID questionImage = image();
        UUID optionImage = image();
        ExamState created = load(examId);
        QuestionDTO first = mc(null, "first", List.of(option(null, "a", true), option(null, "b", false)));
        first.setMedia(List.of(QuestionMediaRef.builder().mediaId(questionImage).build()));
        first.getOptions().get(0).setMediaIds(List.of(optionImage));
        save(examId, PLANNER.forCreate(null, new ArrayList<>(List.of(first, mc(null, "second",
                List.of(option(null, "c", true), option(null, "d", false))), mc(null, "third",
                List.of(option(null, "e", true), option(null, "f", false))))), created.blocks().get(0).id(), false));

        ExamState before = load(examId);
        assertThat(before.blocks()).hasSize(1);
        List<ExamState.QuestionState> q = before.questions();
        assertThat(q).extracting(ExamState.QuestionState::questionText).containsExactly("first", "second", "third");
        long firstId = q.get(0).id();
        long secondId = q.get(1).id();
        long thirdId = q.get(2).id();
        long firstOptionA = q.get(0).options().get(0).id();
        long firstOptionB = q.get(0).options().get(1).id();
        long secondVersion = scalar("SELECT version FROM questions WHERE id = " + secondId);

        // Change the answer key of "first" (b correct now), keep "second" as it is, drop "third", add "fourth"
        QuestionDTO firstChanged = mc(firstId, "first", List.of(option(firstOptionA, "a", false), option(firstOptionB, "b", true),
                option(null, "new option", false)));
        firstChanged.setMedia(List.of(QuestionMediaRef.builder().mediaId(questionImage).build()));
        firstChanged.getOptions().get(0).setMediaIds(List.of(optionImage));
        List<QuestionDTO> request = new ArrayList<>(List.of(firstChanged,
                mc(secondId, "second", List.of(option(q.get(1).options().get(0).id(), "c", true),
                        option(q.get(1).options().get(1).id(), "d", false))),
                mc(null, "fourth", List.of(option(null, "g", true), option(null, "h", false)))));
        save(examId, PLANNER.forUpdate(null, request, before, false));

        ExamState after = load(examId);
        assertThat(after.questions()).extracting(ExamState.QuestionState::questionText).containsExactly("first", "second", "fourth");
        assertThat(after.questions().get(0).id()).isEqualTo(firstId);
        assertThat(after.questions().get(1).id()).isEqualTo(secondId);
        assertThat(scalar("SELECT count(*) FROM questions WHERE id = " + thirdId)).isZero();
        assertThat(after.questions().get(0).options()).extracting(ExamState.OptionState::id)
                .startsWith(firstOptionA, firstOptionB).hasSize(3);
        assertThat(after.questions().get(0).options()).extracting(ExamState.OptionState::correct).containsExactly(false, true, false);
        // Media links stay with their question and option
        assertThat(after.questions().get(0).media()).extracting(ExamState.MediaRef::mediaId).containsExactly(questionImage);
        assertThat(after.questions().get(0).options().get(0).media()).extracting(ExamState.MediaRef::mediaId).containsExactly(optionImage);
        // The unchanged question was not written at all
        assertThat(scalar("SELECT version FROM questions WHERE id = " + secondId)).isEqualTo(secondVersion);
        assertThat(after.questions()).extracting(ExamState.QuestionState::orderIndex).containsExactly(0, 1, 2);
    }

    @Test
    void aQuestionMovesToAnotherBlockAndBlocksSwapPlaces() {
        long examId = exam();
        ExamState created = load(examId);
        ExamBlockDTO one = ExamBlockDTO.builder().title("Riyaziyyat").questions(new ArrayList<>(List.of(
                mc(null, "m1", List.of(option(null, "a", true), option(null, "b", false))),
                mc(null, "m2", List.of(option(null, "a", true), option(null, "b", false)))))).build();
        ExamBlockDTO two = ExamBlockDTO.builder().title("Məntiq").timeLimitMinutes(20).breakAfterMinutes(5)
                .questions(new ArrayList<>(List.of(mc(null, "l1", List.of(option(null, "a", true), option(null, "b", false)))))).build();
        save(examId, PLANNER.forCreate(List.of(one, two), List.of(), created.blocks().get(0).id(), false));

        ExamState before = load(examId);
        assertThat(before.blocks()).extracting(ExamState.BlockState::title).containsExactly("Riyaziyyat", "Məntiq");
        // The break after the last block is always 0
        assertThat(before.blocks().get(1).breakAfterMinutes()).isZero();
        long mathBlock = before.blocks().get(0).id();
        long logicBlock = before.blocks().get(1).id();
        ExamState.QuestionState m2 = before.blocks().get(0).questions().get(1);

        // Logic first, math second; m2 moves into logic
        ExamBlockDTO logic = ExamBlockDTO.builder().id(logicBlock).title("Məntiq").timeLimitMinutes(20).questions(new ArrayList<>(List.of(
                mc(before.blocks().get(1).questions().get(0).id(), "l1", List.of(option(null, "a", true), option(null, "b", false))),
                mc(m2.id(), "m2", List.of(option(m2.options().get(0).id(), "a", true), option(m2.options().get(1).id(), "b", false))))))
                .build();
        ExamBlockDTO math = ExamBlockDTO.builder().id(mathBlock).title("Riyaziyyat").breakAfterMinutes(10).questions(new ArrayList<>(List.of(
                mc(before.blocks().get(0).questions().get(0).id(), "m1", List.of(option(null, "a", true), option(null, "b", false))))))
                .build();
        save(examId, PLANNER.forUpdate(List.of(logic, math), List.of(), before, false));

        ExamState after = load(examId);
        assertThat(after.blocks()).extracting(ExamState.BlockState::id).containsExactly(logicBlock, mathBlock);
        assertThat(after.blocks()).extracting(ExamState.BlockState::position).containsExactly(1, 2);
        assertThat(after.blocks().get(0).questions()).extracting(ExamState.QuestionState::id)
                .containsExactly(before.blocks().get(1).questions().get(0).id(), m2.id());
        assertThat(after.blocks().get(0).questions().get(1).options()).extracting(ExamState.OptionState::id)
                .containsExactly(m2.options().get(0).id(), m2.options().get(1).id());
        assertThat(after.blocks().get(0).breakAfterMinutes()).isZero();
        // The order runs over the blocks: l1, m2, m1
        assertThat(after.questions()).extracting(ExamState.QuestionState::questionText).containsExactly("l1", "m2", "m1");

        // Dropping a block with its question
        ExamBlockDTO onlyLogic = ExamBlockDTO.builder().id(logicBlock).title("Məntiq").questions(new ArrayList<>(List.of(
                mc(m2.id(), "m2", List.of(option(m2.options().get(0).id(), "a", true), option(m2.options().get(1).id(), "b", false))))))
                .build();
        save(examId, PLANNER.forUpdate(List.of(onlyLogic), List.of(), after, false));
        assertThat(load(examId).blocks()).extracting(ExamState.BlockState::id).containsExactly(logicBlock);
        assertThat(scalar("SELECT count(*) FROM exam_blocks WHERE exam_id = " + examId)).isEqualTo(1);
    }

    @Test
    void theOlderFormatNeedsBlocksOnceAnExamHasMoreThanOne() {
        long examId = exam();
        ExamState created = load(examId);
        save(examId, PLANNER.forCreate(List.of(
                ExamBlockDTO.builder().title("A").questions(new ArrayList<>(List.of(mc(null, "a", List.of(option(null, "x", true), option(null, "y", false)))))).build(),
                ExamBlockDTO.builder().title("B").questions(new ArrayList<>(List.of(mc(null, "b", List.of(option(null, "x", true), option(null, "y", false)))))).build()),
                List.of(), created.blocks().get(0).id(), false));
        ExamState twoBlocks = load(examId);
        assertThatThrownBy(() -> PLANNER.forUpdate(null, new ArrayList<>(List.of(mc(null, "z", List.of()))), twoBlocks, false))
                .isInstanceOf(ApplicationException.class)
                .satisfies(e -> assertThat(((ApplicationException) e).getErrorCode()).isEqualTo("BLOCKS_REQUIRED"));

        // An id of another exam is a new question here, never a change of the other exam's row
        long other = exam();
        save(other, PLANNER.forCreate(null, new ArrayList<>(List.of(mc(null, "foreign", List.of(option(null, "x", true), option(null, "y", false))))),
                load(other).blocks().get(0).id(), false));
        long foreignId = load(other).questions().get(0).id();
        long single = exam();
        save(single, PLANNER.forUpdate(null, new ArrayList<>(List.of(mc(foreignId, "mine", List.of(option(null, "x", true), option(null, "y", false))))),
                load(single), false));
        assertThat(load(single).questions().get(0).id()).isNotEqualTo(foreignId);
        assertThat(load(other).questions().get(0).questionText()).isEqualTo("foreign");
    }
}
