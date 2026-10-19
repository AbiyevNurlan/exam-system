package com.examportal.exam.service;

import com.examportal.common.domain.dto.CreateExamRequest;
import com.examportal.common.domain.dto.ExamBlockDTO;
import com.examportal.common.domain.dto.ExamResponse;
import com.examportal.common.domain.dto.QuestionDTO;
import com.examportal.common.domain.dto.media.QuestionMediaRef;
import com.examportal.common.domain.entity.ExamAccessType;
import com.examportal.common.domain.entity.ExamStatus;
import com.examportal.common.exception.ApplicationException;
import com.examportal.exam.history.ChangeContext;
import com.examportal.exam.repository.MediaAssetRepository;
import com.examportal.exam.repository.MediaLinkRepository;
import com.examportal.exam.storage.MediaStorage;
import com.examportal.exam.structure.ExamState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.testcontainers.DockerClientFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * B12a, item 4, on a real PostgreSQL: POST /api/exams/{id}/clone makes a new DRAFT owned by the caller with the
 * blocks, questions, options, answer key, settings and media links (the same media assets), titled "(nüsxə)";
 * audience groups, sessions, results and history are not copied; the copy's history starts with CLONED_FROM.
 * ADMIN or the owner only. A media shared by the source and its copy cannot be deleted (MEDIA_IN_USE), so
 * neither exam loses it. Skipped without Docker.
 */
class ExamCloneTest {

    private static final UUID OWNER = ExamHistoryTest.nurlan().actorId();
    private static ExamTestStack stack;
    private static ExamService service;
    private static MediaService media;

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
        media = new MediaService(repositories.getRepository(MediaAssetRepository.class), new MediaLinkRepository(stack.db),
                new MediaStorage(), mock(MediaTypePolicy.class), stack.db, stack.tx);
    }

    @AfterAll
    static void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    private static UUID image() {
        return stack.db.sql("INSERT INTO media_assets (owner_id, kind, storage_key, content_type, size_bytes, status) "
                        + "VALUES (:o, 'IMAGE', :k, 'image/png', 10, 'ACTIVE') RETURNING id")
                .bind("o", OWNER).bind("k", UUID.randomUUID().toString())
                .map(row -> row.get(0, UUID.class)).one().block();
    }

    private static ExamResponse source(String title, UUID picture) {
        CreateExamRequest request = ExamBlocksServiceTest.create(title);
        request.setDescription("Mənbə");
        request.setShowBlockBreakdown(false);
        QuestionDTO withMedia = ExamHistoryTest.question(null, "Şəkildə nə var?", 2, "2.50");
        withMedia.setMedia(List.of(QuestionMediaRef.builder().mediaId(picture).build()));
        withMedia.getOptions().get(0).setMediaIds(List.of(picture));
        request.setBlocks(List.of(
                ExamBlockDTO.builder().title("Riyaziyyat").instructions("Diqqətlə").questions(new ArrayList<>(List.of(withMedia))).build(),
                ExamBlockDTO.builder().title("Məntiq").showResultToParticipant(false)
                        .questions(new ArrayList<>(List.of(ExamHistoryTest.question(null, "Növbəti?", 0, "1")))).build()));
        return service.createExam(request, ExamHistoryTest.nurlan()).block();
    }

    private static String code(Throwable error) {
        return ((ApplicationException) error).getErrorCode();
    }

    @Test
    void aCopyHasEverythingButAudienceSessionsAndHistory() {
        UUID picture = image();
        ExamResponse source = source("T-b12a clone", picture);
        long group = stack.db.sql("INSERT INTO user_groups (name, created_by, owner_id) VALUES ('T-b12a clone group', :o, :o) RETURNING id")
                .bind("o", OWNER).map(row -> row.get(0, Long.class)).one().block();
        var audience = new com.examportal.common.domain.dto.group.ExamAudienceRequest();
        audience.setAccessType(ExamAccessType.GROUP);
        audience.setGroupIds(List.of(group));
        service.setAudience(source.getId(), audience, ExamHistoryTest.nurlan()).block();
        service.updateExamStatus(source.getId(), ExamStatus.ACTIVE, ExamHistoryTest.nurlan()).block();
        stack.db.sql("INSERT INTO exam_sessions (session_id, exam_id, user_id, status) VALUES (:s, :e, :u, 'COMPLETED')")
                .bind("s", UUID.randomUUID().toString()).bind("e", source.getId()).bind("u", UUID.randomUUID()).then().block();
        long sourceHistory = stack.scalar("SELECT count(*) FROM exam_revisions WHERE exam_id = " + source.getId());

        ExamResponse copy = service.cloneExam(source.getId(), ExamHistoryTest.nurlan()).block();

        assertThat(copy.getId()).isNotEqualTo(source.getId());
        assertThat(copy.getTitle()).isEqualTo("T-b12a clone (nüsxə)");
        assertThat(copy.getStatus()).isEqualTo(ExamStatus.DRAFT);
        assertThat(copy.getCreatedBy()).isEqualTo(OWNER);
        assertThat(copy.getDescription()).isEqualTo("Mənbə");
        assertThat(copy.getShowBlockBreakdown()).isFalse();
        assertThat(copy.getAccessType()).isEqualTo(ExamAccessType.GROUP);
        assertThat(stack.scalar("SELECT count(*) FROM exam_audience WHERE exam_id = " + copy.getId())).isZero();
        assertThat(stack.scalar("SELECT count(*) FROM exam_sessions WHERE exam_id = " + copy.getId())).isZero();

        ExamState from = stack.states.load(source.getId()).block();
        ExamState to = stack.states.load(copy.getId()).block();
        assertThat(to.blocks()).extracting(ExamState.BlockState::title).containsExactly("Riyaziyyat", "Məntiq");
        assertThat(to.blocks().get(0).instructions()).isEqualTo("Diqqətlə");
        assertThat(to.blocks().get(1).showResultToParticipant()).isFalse();
        assertThat(to.questions()).extracting(ExamState.QuestionState::questionText)
                .containsExactlyElementsOf(from.questions().stream().map(ExamState.QuestionState::questionText).toList());
        assertThat(to.questions().get(0).points()).isEqualByComparingTo("2.50");
        assertThat(to.questions().get(0).options()).extracting(ExamState.OptionState::correct).containsExactly(false, false, true, false);
        // New rows everywhere
        Set<Long> sourceIds = new HashSet<>(from.questions().stream().map(ExamState.QuestionState::id).toList());
        assertThat(to.questions()).extracting(ExamState.QuestionState::id).doesNotContainAnyElementsOf(sourceIds);
        assertThat(to.blocks()).extracting(ExamState.BlockState::id)
                .doesNotContainAnyElementsOf(from.blocks().stream().map(ExamState.BlockState::id).toList());
        // The same media asset, for the question and for its option
        assertThat(to.questions().get(0).media()).extracting(ExamState.MediaRef::mediaId).containsExactly(picture);
        assertThat(to.questions().get(0).options().get(0).media()).extracting(ExamState.MediaRef::mediaId).containsExactly(picture);

        // The copy's history is only its origin; the source's history is unchanged
        assertThat(stack.db.sql("SELECT action FROM exam_revisions WHERE exam_id = " + copy.getId())
                .map(row -> row.get(0, String.class)).all().collectList().block()).containsExactly("CLONED_FROM");
        assertThat(stack.scalar("SELECT count(*) FROM exam_revisions WHERE exam_id = " + source.getId())).isEqualTo(sourceHistory);

        // ADMIN may copy anyone's exam (and owns the copy); another INSTRUCTOR may not
        UUID admin = UUID.randomUUID();
        assertThat(service.cloneExam(source.getId(), ChangeContext.of(admin, "ADMIN")).block().getCreatedBy()).isEqualTo(admin);
        stack.db.sql("UPDATE exams SET access_type = 'PUBLIC' WHERE id = " + source.getId()).then().block();
        assertThatThrownBy(() -> service.cloneExam(source.getId(), ChangeContext.of(UUID.randomUUID(), "INSTRUCTOR")).block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("EXAM_NOT_OWNER"));
    }

    @Test
    void aSharedMediaCannotBeDeletedFromUnderTheOtherExam() {
        UUID picture = image();
        ExamResponse draft = source("T-b12a clone media", picture);
        ExamResponse copy = service.cloneExam(draft.getId(), ExamHistoryTest.nurlan()).block();

        // Both are drafts: before B12a the delete would have taken the media from both
        assertThatThrownBy(() -> media.delete(picture, OWNER, "INSTRUCTOR").block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("MEDIA_IN_USE"))
                .hasMessageContaining("more than one exam");
        assertThat(stack.states.load(draft.getId()).block().questions().get(0).media()).hasSize(1);
        assertThat(stack.states.load(copy.getId()).block().questions().get(0).media()).hasSize(1);
        assertThat(stack.scalar("SELECT count(*) FROM media_assets WHERE status = 'ACTIVE' AND id = '" + picture + "'")).isEqualTo(1);

        // Once only one exam uses it, it can go as before
        service.deleteExam(copy.getId(), ExamHistoryTest.nurlan()).block();
        media.delete(picture, OWNER, "INSTRUCTOR").block();
        assertThat(stack.scalar("SELECT count(*) FROM media_assets WHERE status = 'DELETED' AND id = '" + picture + "'")).isEqualTo(1);
    }
}
