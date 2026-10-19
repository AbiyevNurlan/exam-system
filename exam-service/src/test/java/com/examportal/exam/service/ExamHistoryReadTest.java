package com.examportal.exam.service;

import com.examportal.common.domain.dto.CreateExamRequest;
import com.examportal.common.domain.dto.ExamResponse;
import com.examportal.common.domain.dto.ManagedExamResponse;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.QuestionDTO;
import com.examportal.common.domain.dto.UpdateExamRequest;
import com.examportal.common.domain.dto.history.ExamHistoryDtos;
import com.examportal.common.domain.entity.ExamStatus;
import com.examportal.common.exception.ApplicationException;
import com.examportal.exam.controller.ExamHistoryController;
import com.examportal.exam.history.ExamHistoryQueryRepository;
import com.examportal.exam.history.ExamHistoryQueryService;
import com.examportal.exam.history.ExamSnapshotRepository;
import com.examportal.exam.repository.ExamManageRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.testcontainers.DockerClientFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B12a, item 3f, on a real PostgreSQL: reading the history and the versions. ADMIN every exam, the owning
 * INSTRUCTOR the own one, another INSTRUCTOR 404 (also after the exam was deleted), STUDENT 403 by role; the
 * filters (actor by id or code, action, sensitiveOnly, entityType, days); the detail with changes, ip only for
 * ADMIN; versions with hashValid; lastModifiedBy on the management list; lastEditedBy on questions for managers
 * only. Skipped without Docker.
 */
class ExamHistoryReadTest {

    private static final UUID OWNER = ExamHistoryTest.nurlan().actorId();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static ExamTestStack stack;
    private static ExamService service;
    private static ExamHistoryQueryService reader;

    @BeforeAll
    static void start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        stack = ExamTestStack.start();
        service = stack.examService(new ExamFeatures(false, false));
        reader = new ExamHistoryQueryService(stack.exams, new ExamHistoryQueryRepository(stack.db), new ExamSnapshotRepository(stack.db));
    }

    @AfterAll
    static void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    private static ExamResponse examWithHistory(String title) {
        CreateExamRequest request = ExamBlocksServiceTest.create(title);
        request.setQuestions(new ArrayList<>(List.of(ExamHistoryTest.question(null, "Bir", 0, "1"),
                ExamHistoryTest.question(null, "İki", 1, "1"))));
        ExamResponse exam = service.createExam(request, ExamHistoryTest.nurlan()).block();
        UpdateExamRequest change = ExamHistoryTest.unchanged(exam);
        QuestionDTO second = change.getBlocks().get(0).getQuestions().get(1);
        second.setPoints(new BigDecimal("3"));
        service.updateExam(exam.getId(), change, ExamHistoryTest.nurlan()).block();
        service.updateExamStatus(exam.getId(), ExamStatus.ACTIVE, ExamHistoryTest.nurlan()).block();
        return exam;
    }

    private static PageResponse<ExamHistoryDtos.Entry> page(long examId, UUID user, String role, String actor, String action,
                                                           Boolean sensitive, String entityType) {
        return reader.page(examId, user, role, actor, action, null, null, sensitive, entityType, 0, 50).block();
    }

    private static String code(Throwable error) {
        return ((ApplicationException) error).getErrorCode();
    }

    @Test
    void whoMayReadAndTheFilters() {
        ExamResponse exam = examWithHistory("T-b12a read");
        long id = exam.getId();

        PageResponse<ExamHistoryDtos.Entry> all = page(id, ADMIN, "ADMIN", null, null, null, null);
        // EXAM_CREATED, EXAM_UPDATED, POINTS_CHANGED, PUBLISHED; newest first
        assertThat(all.getContent()).extracting(ExamHistoryDtos.Entry::getAction)
                .containsExactly("PUBLISHED", "POINTS_CHANGED", "EXAM_UPDATED", "EXAM_CREATED");
        assertThat(all.getTotalElements()).isEqualTo(4);
        assertThat(all.getContent().get(0).getActor().getName()).isEqualTo("Nurlan Abiyev");
        assertThat(all.getContent().get(0).getChanges()).isNull();
        assertThat(page(id, OWNER, "INSTRUCTOR", null, null, null, null).getTotalElements()).isEqualTo(4);
        assertThatThrownBy(() -> page(id, OTHER, "INSTRUCTOR", null, null, null, null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("EXAM_NOT_FOUND"));
        assertThatThrownBy(() -> page(id, OTHER, "STUDENT", null, null, null, null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("EXAM_NOT_FOUND"));
        // A STUDENT never gets that far: the controller (and SecurityConfig by path) answer 403
        assertThat(ExamHistoryController.class.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAnyRole('ADMIN', 'INSTRUCTOR')");

        assertThat(page(id, ADMIN, "ADMIN", null, "published", null, null).getContent()).extracting(ExamHistoryDtos.Entry::getAction)
                .containsExactly("PUBLISHED");
        assertThat(page(id, ADMIN, "ADMIN", null, null, true, null).getContent()).extracting(ExamHistoryDtos.Entry::getAction)
                .containsExactly("PUBLISHED", "POINTS_CHANGED", "EXAM_UPDATED");
        assertThat(page(id, ADMIN, "ADMIN", null, null, null, "QUESTION").getContent()).extracting(ExamHistoryDtos.Entry::getAction)
                .containsExactly("POINTS_CHANGED");
        assertThat(page(id, ADMIN, "ADMIN", "1234567", null, null, null).getTotalElements()).isEqualTo(4);
        assertThat(page(id, ADMIN, "ADMIN", OWNER.toString(), null, null, null).getTotalElements()).isEqualTo(4);
        assertThat(page(id, ADMIN, "ADMIN", "7654321", null, null, null).getTotalElements()).isZero();
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        assertThat(reader.page(id, ADMIN, "ADMIN", null, null, today, today, null, null, 0, 50).block().getTotalElements()).isEqualTo(4);
        assertThat(reader.page(id, ADMIN, "ADMIN", null, null, "2020-01-01", "2020-01-02", null, null, 0, 50).block().getTotalElements()).isZero();
        assertThatThrownBy(() -> page(id, ADMIN, "ADMIN", null, "NOT_AN_ACTION", null, null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_ERROR"));
        assertThatThrownBy(() -> page(id, ADMIN, "ADMIN", "nobody", null, null, null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_ERROR"));

        // The detail: changes; ip, user agent and request id only for ADMIN
        ExamHistoryDtos.Entry points = all.getContent().get(1);
        ExamHistoryDtos.Entry asAdmin = reader.one(id, points.getId(), ADMIN, "ADMIN").block();
        assertThat(((Map<?, ?>) asAdmin.getChanges()).containsKey("points")).isTrue();
        assertThat(asAdmin.getIp()).isEqualTo("10.0.0.7");
        ExamHistoryDtos.Entry asOwner = reader.one(id, points.getId(), OWNER, "INSTRUCTOR").block();
        assertThat(asOwner.getChanges()).isNotNull();
        assertThat(asOwner.getIp()).isNull();
        assertThatThrownBy(() -> reader.one(id, points.getId(), OTHER, "INSTRUCTOR").block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("EXAM_NOT_FOUND"));
        // A row of another exam through this exam's path is 404
        ExamResponse other = examWithHistory("T-b12a read other");
        long foreignRow = page(other.getId(), ADMIN, "ADMIN", null, null, null, null).getContent().get(0).getId();
        assertThatThrownBy(() -> reader.one(id, foreignRow, ADMIN, "ADMIN").block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("EXAM_NOT_FOUND"));

        // Versions
        List<ExamHistoryDtos.Version> versions = reader.versions(id, OWNER, "INSTRUCTOR").block();
        assertThat(versions).extracting(ExamHistoryDtos.Version::getVersionNo).containsExactly(1);
        ExamHistoryDtos.Version first = reader.version(id, 1, ADMIN, "ADMIN").block();
        assertThat(first.getHashValid()).isTrue();
        assertThat(first.getSnapshot()).isNotNull();
        assertThatThrownBy(() -> reader.versions(id, OTHER, "INSTRUCTOR").block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("EXAM_NOT_FOUND"));
        assertThatThrownBy(() -> reader.version(id, 9, ADMIN, "ADMIN").block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("EXAM_NOT_FOUND"));
    }

    @Test
    void theHistoryOfADeletedExamStaysWithItsOwner() {
        CreateExamRequest request = ExamBlocksServiceTest.create("T-b12a read deleted");
        request.setQuestions(new ArrayList<>(List.of(ExamHistoryTest.question(null, "Bir", 0, "1"))));
        long id = service.createExam(request, ExamHistoryTest.nurlan()).block().getId();
        service.deleteExam(id, ExamHistoryTest.nurlan()).block();

        assertThat(page(id, OWNER, "INSTRUCTOR", null, null, null, null).getContent()).extracting(ExamHistoryDtos.Entry::getAction)
                .containsExactly("DELETE_ATTEMPTED", "EXAM_CREATED");
        assertThat(page(id, ADMIN, "ADMIN", null, null, null, null).getTotalElements()).isEqualTo(2);
        assertThatThrownBy(() -> page(id, OTHER, "INSTRUCTOR", null, null, null, null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("EXAM_NOT_FOUND"));
        // An exam that never existed
        assertThatThrownBy(() -> page(987654321L, ADMIN, "ADMIN", null, null, null, null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("EXAM_NOT_FOUND"));
    }

    @Test
    void lastModifiedAndLastEdited() {
        ExamResponse exam = examWithHistory("T-b12a read last");
        ManagedExamResponse row = new ExamManageRepository(stack.db).findPage(
                new ExamManageRepository.Filter(OWNER, null, "%T-b12a read last%"), 10, 0).blockFirst();
        assertThat(row.getLastModifiedBy().getName()).isEqualTo("Nurlan Abiyev");
        assertThat(row.getLastModifiedBy().getCode()).isEqualTo(1234567);
        assertThat(row.getLastModifiedAt()).isNotNull();

        stack.cache.invalidateExamCache(exam.getId()).block();
        ExamResponse forOwner = service.getExamById(exam.getId(), OWNER, "INSTRUCTOR").block();
        // The second question had its points changed; the first only came with the exam (no row of its own)
        assertThat(forOwner.getQuestions().get(1).getLastEditedBy().getCode()).isEqualTo(1234567);
        assertThat(forOwner.getQuestions().get(1).getLastEditedAt()).isNotNull();
        assertThat(forOwner.getQuestions().get(0).getLastEditedBy()).isNull();
        // Participants never get it (the exam is ACTIVE and PUBLIC)
        ExamResponse forParticipant = service.getExamById(exam.getId(), UUID.randomUUID(), "STUDENT").block();
        assertThat(forParticipant.getQuestions()).allSatisfy(q -> assertThat(q.getLastEditedBy()).isNull());
    }
}
