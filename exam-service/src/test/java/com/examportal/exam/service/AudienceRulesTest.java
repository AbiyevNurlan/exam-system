package com.examportal.exam.service;

import com.examportal.common.domain.dto.CreateExamRequest;
import com.examportal.common.domain.dto.ExamResponse;
import com.examportal.common.domain.dto.UpdateExamRequest;
import com.examportal.common.domain.dto.group.ExamAudienceRequest;
import com.examportal.common.domain.entity.ExamAccessType;
import com.examportal.common.exception.ApplicationException;
import com.examportal.exam.history.ChangeContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.testcontainers.DockerClientFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B13, item 5, on a real PostgreSQL: only ADMIN makes an exam PUBLIC. A teacher who asks for PUBLIC (create,
 * update, audience) gets 403 PUBLIC_EXAM_ADMIN_ONLY and nothing is stored; without an access type a teacher's exam
 * is GROUP (no groups yet), ADMIN's PUBLIC as before; an exam that is PUBLIC already stays so when its owner saves
 * it; a teacher's copy of a PUBLIC exam is GROUP; another teacher's group in the audience is the same answer
 * (status, AUDIENCE_GROUP_NOT_FOUND, message) as a group that does not exist. Skipped without Docker.
 */
class AudienceRulesTest {

    private static final UUID TEACHER = UUID.randomUUID();
    private static final UUID OTHER_TEACHER = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
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

    private static ChangeContext as(UUID user, String role) {
        return ChangeContext.of(user, role);
    }

    private static CreateExamRequest request(String title, ExamAccessType accessType, List<Long> groups) {
        CreateExamRequest request = ExamBlocksServiceTest.create(title);
        request.setQuestions(new ArrayList<>(List.of(ExamHistoryTest.question(null, "1 + 1?", 1, "1"))));
        request.setAccessType(accessType);
        request.setGroupIds(groups);
        return request;
    }

    private static long group(String name, UUID owner) {
        return stack.db.sql("INSERT INTO user_groups (name, created_by, owner_id) VALUES (:n, :o, :o) RETURNING id")
                .bind("n", name).bind("o", owner).map(row -> row.get(0, Long.class)).one().block();
    }

    private static void assertCode(Throwable error, HttpStatus status, String code) {
        assertThat(error).isInstanceOf(ApplicationException.class);
        assertThat(((ApplicationException) error).getStatus()).isEqualTo(status);
        assertThat(((ApplicationException) error).getErrorCode()).isEqualTo(code);
    }

    private static String accessTypeOf(long examId) {
        return stack.db.sql("SELECT access_type FROM exams WHERE id = :id").bind("id", examId)
                .map(row -> row.get(0, String.class)).one().block();
    }

    @Test
    void a_teacher_cannot_create_a_public_exam_and_nothing_is_stored() {
        long before = stack.scalar("SELECT count(*) FROM exams");
        assertThatThrownBy(() -> service.createExam(request("T-b13 public", ExamAccessType.PUBLIC, null),
                as(TEACHER, "INSTRUCTOR")).block())
                .satisfies(e -> assertCode(e, HttpStatus.FORBIDDEN, "PUBLIC_EXAM_ADMIN_ONLY"));
        assertThat(stack.scalar("SELECT count(*) FROM exams")).isEqualTo(before);
    }

    @Test
    void without_an_access_type_a_teacher_gets_group_and_admin_public() {
        ExamResponse teacher = service.createExam(request("T-b13 default teacher", null, null), as(TEACHER, "INSTRUCTOR")).block();
        assertThat(accessTypeOf(teacher.getId())).isEqualTo("GROUP");
        assertThat(stack.scalar("SELECT count(*) FROM exam_audience WHERE exam_id = " + teacher.getId())).isZero();

        ExamResponse admin = service.createExam(request("T-b13 default admin", null, null), as(ADMIN, "ADMIN")).block();
        assertThat(accessTypeOf(admin.getId())).isEqualTo("PUBLIC");

        // An explicit GROUP without groups is still refused (unchanged contract)
        assertThatThrownBy(() -> service.createExam(request("T-b13 empty group", ExamAccessType.GROUP, List.of()),
                as(TEACHER, "INSTRUCTOR")).block())
                .satisfies(e -> assertThat(((ApplicationException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void a_teacher_cannot_turn_an_exam_public_but_keeps_one_that_is_public_already() {
        long own = group("T-b13 own group", TEACHER);
        ExamResponse exam = service.createExam(request("T-b13 group exam", ExamAccessType.GROUP, List.of(own)),
                as(TEACHER, "INSTRUCTOR")).block();

        ExamAudienceRequest toPublic = new ExamAudienceRequest();
        toPublic.setAccessType(ExamAccessType.PUBLIC);
        assertThatThrownBy(() -> service.setAudience(exam.getId(), toPublic, as(TEACHER, "INSTRUCTOR")).block())
                .satisfies(e -> assertCode(e, HttpStatus.FORBIDDEN, "PUBLIC_EXAM_ADMIN_ONLY"));
        UpdateExamRequest update = ExamBlocksServiceTest.update(exam);
        update.setQuestions(new ArrayList<>(List.of(ExamHistoryTest.question(exam.getQuestions().get(0).getId(), "1 + 1?", 1, "1"))));
        update.setAccessType(ExamAccessType.PUBLIC);
        assertThatThrownBy(() -> service.updateExam(exam.getId(), update, as(TEACHER, "INSTRUCTOR")).block())
                .satisfies(e -> assertCode(e, HttpStatus.FORBIDDEN, "PUBLIC_EXAM_ADMIN_ONLY"));
        assertThat(accessTypeOf(exam.getId())).isEqualTo("GROUP");

        // ADMIN may; afterwards the teacher saves the exam that is PUBLIC now and it stays PUBLIC
        service.setAudience(exam.getId(), toPublic, as(ADMIN, "ADMIN")).block();
        assertThat(accessTypeOf(exam.getId())).isEqualTo("PUBLIC");
        UpdateExamRequest keep = ExamBlocksServiceTest.update(exam);
        keep.setQuestions(new ArrayList<>(List.of(ExamHistoryTest.question(exam.getQuestions().get(0).getId(), "1 + 2?", 2, "1"))));
        keep.setAccessType(ExamAccessType.PUBLIC);
        service.updateExam(exam.getId(), keep, as(TEACHER, "INSTRUCTOR")).block();
        assertThat(accessTypeOf(exam.getId())).isEqualTo("PUBLIC");

        // A teacher's copy of it is GROUP without groups; ADMIN's copy stays PUBLIC
        ExamResponse copy = service.cloneExam(exam.getId(), as(TEACHER, "INSTRUCTOR")).block();
        assertThat(accessTypeOf(copy.getId())).isEqualTo("GROUP");
        assertThat(stack.scalar("SELECT count(*) FROM exam_audience WHERE exam_id = " + copy.getId())).isZero();
        ExamResponse adminCopy = service.cloneExam(exam.getId(), as(ADMIN, "ADMIN")).block();
        assertThat(accessTypeOf(adminCopy.getId())).isEqualTo("PUBLIC");
    }

    @Test
    void another_teachers_group_is_the_same_404_as_a_missing_group() {
        long foreign = group("T-b13 foreign group", OTHER_TEACHER);
        Throwable withForeign = org.assertj.core.api.Assertions.catchThrowable(() -> service.createExam(
                request("T-b13 foreign audience", ExamAccessType.GROUP, List.of(foreign)), as(TEACHER, "INSTRUCTOR")).block());
        Throwable withMissing = org.assertj.core.api.Assertions.catchThrowable(() -> service.createExam(
                request("T-b13 missing audience", ExamAccessType.GROUP, List.of(Long.MAX_VALUE)), as(TEACHER, "INSTRUCTOR")).block());
        assertThat(((ApplicationException) withForeign).getErrorCode()).isEqualTo("AUDIENCE_GROUP_NOT_FOUND");
        assertCode(withMissing, ((ApplicationException) withForeign).getStatus(), "AUDIENCE_GROUP_NOT_FOUND");
        assertThat(withForeign.getMessage()).isEqualTo(withMissing.getMessage());
    }
}
