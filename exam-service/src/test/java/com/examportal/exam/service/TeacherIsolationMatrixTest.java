package com.examportal.exam.service;

import com.examportal.common.domain.dto.CreateExamRequest;
import com.examportal.common.domain.dto.ExamBlockDTO;
import com.examportal.common.domain.dto.ExamMetaRequest;
import com.examportal.common.domain.dto.ExamResponse;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.group.AddGroupMemberRequest;
import com.examportal.common.domain.dto.group.ExamAudienceRequest;
import com.examportal.common.domain.dto.group.GroupRequest;
import com.examportal.common.domain.dto.group.GroupResponse;
import com.examportal.common.domain.dto.group.ParticipantDtos;
import com.examportal.common.domain.dto.internal.ParticipantQuery;
import com.examportal.common.domain.entity.ExamAccessType;
import com.examportal.common.domain.entity.ExamStatus;
import com.examportal.common.exception.ApplicationException;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.client.UserDirectoryClient;
import com.examportal.exam.history.ChangeContext;
import com.examportal.exam.repository.GroupMembershipRepository;
import com.examportal.exam.repository.GroupOverviewRepository;
import com.examportal.exam.repository.PendingGroupInviteRepository;
import com.examportal.exam.repository.UserGroupMemberRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.http.HttpStatus;
import org.testcontainers.DockerClientFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B13, the matrix of two teachers on a real PostgreSQL: teacher B asks for every exam, session and group of teacher
 * A (read, change, delete, copy, audience, members, participants) and always gets the same 404 as for an id that
 * does not exist, nothing is written; the lists of B hold none of A's rows; ADMIN reaches both. Skipped without
 * Docker.
 */
class TeacherIsolationMatrixTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID MEMBER_OF_A = UUID.randomUUID();
    private static final long MISSING = 987_654_321L;

    private static ExamTestStack stack;
    private static ExamService exams;
    private static ExamMetaService meta;
    private static GroupService groups;
    private static ParticipantService participants;
    private static final UserDirectoryClient directory = mock(UserDirectoryClient.class);

    private static long examOfA;
    private static long groupOfA;
    private static String sessionOfA;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        stack = ExamTestStack.start();
        exams = stack.examService(new ExamFeatures(false, false));
        meta = stack.metaService(new ExamFeatures(false, false));
        R2dbcRepositoryFactory repositories = new R2dbcRepositoryFactory(new R2dbcEntityTemplate(
                new io.r2dbc.postgresql.PostgresqlConnectionFactory(io.r2dbc.postgresql.PostgresqlConnectionConfiguration.builder()
                        .host(stack.postgres.getHost()).port(stack.postgres.getMappedPort(5432))
                        .database(stack.postgres.getDatabaseName()).username(stack.postgres.getUsername())
                        .password(stack.postgres.getPassword()).build())));
        GroupMembershipRepository membership = new GroupMembershipRepository(stack.db);
        groups = new GroupService(stack.groups, repositories.getRepository(UserGroupMemberRepository.class),
                repositories.getRepository(PendingGroupInviteRepository.class), stack.groupAudit, directory, stack.tx,
                new GroupOverviewRepository(stack.db), membership, new GroupAccessPolicy(stack.groups),
                new ActionRateLimiter(mock(ObjectProvider.class)), GroupMemberAddTest.unlimited());
        participants = new ParticipantService(directory, membership, stack.groups, new ExamDayClock("Asia/Baku"));

        // Teacher A: a group with one member, an exam for that group, a finished session of a participant
        groupOfA = groups.createGroup(GroupRequest.builder().name("T-b13 matrix A").build(), teacher(A)).block().getId();
        stack.db.sql("INSERT INTO user_group_members (group_id, user_id, source, added_by) VALUES (:g, :u, 'ADMIN', :a)")
                .bind("g", groupOfA).bind("u", MEMBER_OF_A).bind("a", A).then().block();
        examOfA = exams.createExam(exam("T-b13 matrix exam A"), ChangeContext.of(A, "INSTRUCTOR")).block().getId();
        ExamAudienceRequest audience = new ExamAudienceRequest();
        audience.setAccessType(ExamAccessType.GROUP);
        audience.setGroupIds(List.of(groupOfA));
        exams.setAudience(examOfA, audience, ChangeContext.of(A, "INSTRUCTOR")).block();
        sessionOfA = UUID.randomUUID().toString();
        stack.db.sql("INSERT INTO exam_sessions (session_id, exam_id, user_id, status) VALUES (:s, :e, :u, 'COMPLETED')")
                .bind("s", sessionOfA).bind("e", examOfA).bind("u", MEMBER_OF_A).then().block();
        // Teacher B has the own group and exam, so B's lists are not empty by accident
        groups.createGroup(GroupRequest.builder().name("T-b13 matrix B").build(), teacher(B)).block();
        exams.createExam(exam("T-b13 matrix exam B"), ChangeContext.of(B, "INSTRUCTOR")).block();
    }

    @AfterAll
    static void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    private static CreateExamRequest exam(String title) {
        CreateExamRequest request = ExamBlocksServiceTest.create(title);
        request.setBlocks(List.of(ExamBlockDTO.builder().title("Blok")
                .questions(new ArrayList<>(List.of(ExamHistoryTest.question(null, "Sual?", 0, "1")))).build()));
        return request;
    }

    private static UserPrincipal teacher(UUID id) {
        return UserPrincipal.builder().id(id).role("INSTRUCTOR").build();
    }

    private static ChangeContext b() {
        return ChangeContext.of(B, "INSTRUCTOR");
    }

    /** The call fails with the given 404 for A's id and with the very same status and code for an id that does not exist */
    private static void sameAsMissing(String code, Supplier<Mono<?>> forA, Supplier<Mono<?>> forMissing) {
        Throwable onA = catchError(forA);
        Throwable onMissing = catchError(forMissing);
        assertThat(onA).as("another teacher's row").isInstanceOf(ApplicationException.class);
        assertThat(((ApplicationException) onA).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ApplicationException) onA).getErrorCode()).isEqualTo(code);
        assertThat(((ApplicationException) onMissing).getStatus()).isEqualTo(((ApplicationException) onA).getStatus());
        assertThat(((ApplicationException) onMissing).getErrorCode()).isEqualTo(((ApplicationException) onA).getErrorCode());
    }

    private static Throwable catchError(Supplier<Mono<?>> call) {
        try {
            call.get().block();
        } catch (Throwable error) {
            return error;
        }
        throw new AssertionError("expected an error, the call succeeded");
    }

    private static ExamMetaRequest retitle() {
        ExamMetaRequest request = new ExamMetaRequest();
        request.setTitle("T-b13 taken over");
        return request;
    }

    @Test
    void teacher_b_reaches_no_exam_of_teacher_a() {
        String exam = "EXAM_NOT_FOUND";
        sameAsMissing(exam, () -> exams.getExamById(examOfA, B, "INSTRUCTOR"), () -> exams.getExamById(MISSING, B, "INSTRUCTOR"));
        sameAsMissing(exam, () -> meta.updateMeta(examOfA, retitle(), B, "INSTRUCTOR"), () -> meta.updateMeta(MISSING, retitle(), B, "INSTRUCTOR"));
        sameAsMissing(exam, () -> exams.updateExamStatus(examOfA, ExamStatus.ARCHIVED, b()), () -> exams.updateExamStatus(MISSING, ExamStatus.ARCHIVED, b()));
        sameAsMissing(exam, () -> exams.deleteExam(examOfA, b()), () -> exams.deleteExam(MISSING, b()));
        sameAsMissing(exam, () -> exams.cloneExam(examOfA, b()), () -> exams.cloneExam(MISSING, b()));
        ExamAudienceRequest everyone = new ExamAudienceRequest();
        everyone.setAccessType(ExamAccessType.GROUP);
        everyone.setGroupIds(List.of());
        sameAsMissing(exam, () -> exams.setAudience(examOfA, everyone, b()), () -> exams.setAudience(MISSING, everyone, b()));
        sameAsMissing(exam, () -> exams.previewAudience(examOfA, List.of(), B, "INSTRUCTOR"), () -> exams.previewAudience(MISSING, List.of(), B, "INSTRUCTOR"));
        sameAsMissing(exam, () -> stack.accessPolicy.findManageableExam(examOfA, B, "INSTRUCTOR"), () -> stack.accessPolicy.findManageableExam(MISSING, B, "INSTRUCTOR"));
        sameAsMissing("SESSION_NOT_FOUND", () -> stack.accessPolicy.findManageableSession(sessionOfA, B, "INSTRUCTOR"),
                () -> stack.accessPolicy.findManageableSession(UUID.randomUUID().toString(), B, "INSTRUCTOR"));

        // Nothing was written: A's exam keeps its title, status, audience; no copy for B
        assertThat(stack.scalar("SELECT count(*) FROM exams WHERE id = " + examOfA
                + " AND title = 'T-b13 matrix exam A' AND status = 'DRAFT' AND active = true")).isEqualTo(1);
        assertThat(stack.scalar("SELECT count(*) FROM exam_audience WHERE exam_id = " + examOfA)).isEqualTo(1);
        assertThat(stack.db.sql("SELECT count(*) FROM exams WHERE created_by = :b").bind("b", B)
                .map(row -> ((Number) row.get(0)).longValue()).one().block()).isEqualTo(1);
    }

    @Test
    void teacher_b_cannot_put_a_group_of_teacher_a_into_the_own_exam() {
        long own = stack.db.sql("SELECT id FROM exams WHERE created_by = :b").bind("b", B).map(row -> ((Number) row.get(0)).longValue()).one().block();
        ExamAudienceRequest foreign = new ExamAudienceRequest();
        foreign.setAccessType(ExamAccessType.GROUP);
        foreign.setGroupIds(List.of(groupOfA));
        ExamAudienceRequest missing = new ExamAudienceRequest();
        missing.setAccessType(ExamAccessType.GROUP);
        missing.setGroupIds(List.of(MISSING));

        Throwable onA = catchError(() -> exams.setAudience(own, foreign, b()));
        Throwable onMissing = catchError(() -> exams.setAudience(own, missing, b()));
        assertThat(((ApplicationException) onA).getErrorCode()).isEqualTo(((ApplicationException) onMissing).getErrorCode());
        assertThat(((ApplicationException) onA).getStatus()).isEqualTo(((ApplicationException) onMissing).getStatus());
        // The preview lists another owner's group as unknown, exactly like a group that does not exist
        var preview = exams.previewGroups(List.of(groupOfA), B, "INSTRUCTOR").block();
        assertThat(preview.getPerGroup()).isEmpty();
        assertThat(preview.getDistinctEligible()).isZero();
        assertThat(preview.getUnknownGroupIds()).containsExactly(groupOfA);
        assertThat(exams.previewGroups(List.of(MISSING), B, "INSTRUCTOR").block().getUnknownGroupIds()).containsExactly(MISSING);
        assertThat(stack.scalar("SELECT count(*) FROM exam_audience WHERE exam_id = " + own)).isZero();
    }

    @Test
    void teacher_b_reaches_no_group_of_teacher_a() {
        String group = "GROUP_NOT_FOUND";
        UserPrincipal b = teacher(B);
        sameAsMissing(group, () -> groups.getGroup(groupOfA, b), () -> groups.getGroup(MISSING, b));
        sameAsMissing(group, () -> groups.updateGroup(groupOfA, GroupRequest.builder().name("T-b13 taken").build(), b),
                () -> groups.updateGroup(MISSING, GroupRequest.builder().name("T-b13 taken").build(), b));
        sameAsMissing(group, () -> groups.deleteGroup(groupOfA, b), () -> groups.deleteGroup(MISSING, b));
        sameAsMissing(group, () -> groups.getMembers(groupOfA, 0, 20, b), () -> groups.getMembers(MISSING, 0, 20, b));
        sameAsMissing(group, () -> groups.getGroupExams(groupOfA, 0, 20, b), () -> groups.getGroupExams(MISSING, 0, 20, b));
        sameAsMissing(group, () -> groups.getInvites(groupOfA, 0, 20, b), () -> groups.getInvites(MISSING, 0, 20, b));
        AddGroupMemberRequest add = AddGroupMemberRequest.builder().email("t-b13@x.test").build();
        sameAsMissing(group, () -> groups.addMember(groupOfA, add, b), () -> groups.addMember(MISSING, add, b));
        sameAsMissing(group, () -> groups.removeMember(groupOfA, MEMBER_OF_A, b), () -> groups.removeMember(MISSING, MEMBER_OF_A, b));

        assertThat(stack.scalar("SELECT count(*) FROM user_groups WHERE id = " + groupOfA
                + " AND name = 'T-b13 matrix A' AND active = true")).isEqualTo(1);
        assertThat(stack.scalar("SELECT count(*) FROM user_group_members WHERE group_id = " + groupOfA)).isEqualTo(1);
        assertThat(stack.scalar("SELECT count(*) FROM pending_group_invites WHERE group_id = " + groupOfA)).isZero();
    }

    @Test
    void the_lists_of_teacher_b_hold_nothing_of_teacher_a_and_admin_sees_both() {
        PageResponse<GroupResponse> groupsOfB = groups.searchGroups(null, 0, 100, teacher(B)).block();
        assertThat(groupsOfB.getContent()).extracting(GroupResponse::getName).containsExactly("T-b13 matrix B");
        assertThat(groups.listGroupNames(null, 0, 100, teacher(B)).block().getContent()).hasSize(1);
        assertThat(exams.getManagedExams(B, "INSTRUCTOR", null, null, 0, 100).block().getContent())
                .extracting(row -> row.getTitle()).containsExactly("T-b13 matrix exam B");

        PageResponse<GroupResponse> all = groups.searchGroups("T-b13 matrix", 0, 100,
                UserPrincipal.builder().id(ADMIN).role("ADMIN").build()).block();
        assertThat(all.getContent()).extracting(GroupResponse::getName).contains("T-b13 matrix A", "T-b13 matrix B");
        assertThat(exams.getManagedExams(ADMIN, "ADMIN", "T-b13 matrix", null, 0, 100).block().getContent()).hasSize(2);
        assertThat(stack.accessPolicy.findManageableExam(examOfA, ADMIN, "ADMIN").block().getId()).isEqualTo(examOfA);
    }

    @Test
    void teacher_b_never_gets_a_participant_of_teacher_a() {
        reset(directory);
        when(directory.participants(any())).thenReturn(Mono.just(ParticipantQuery.PageResponse.builder().users(List.of()).total(0).build()));

        // B has no members: an empty page, auth-service is not even asked
        PageResponse<ParticipantDtos.Participant> page = participants.getParticipants(new ParticipantDtos.Filter(), null, null, null,
                null, 0, 25, B, "INSTRUCTOR").block();
        assertThat(page.getContent()).isEmpty();
        assertThat(participants.ownMembersAmong(List.of(MEMBER_OF_A), B, "INSTRUCTOR").block()).isEmpty();
        assertThat(participants.ownMembersAmong(List.of(MEMBER_OF_A), A, "INSTRUCTOR").block()).containsExactly(MEMBER_OF_A);

        // A filter on A's group is the same 404 as a missing group
        Throwable onA = catchError(() -> participants.getParticipants(ParticipantDtos.Filter.builder().groupId(groupOfA).build(),
                null, null, null, null, 0, 25, B, "INSTRUCTOR"));
        Throwable onMissing = catchError(() -> participants.getParticipants(ParticipantDtos.Filter.builder().groupId(MISSING).build(),
                null, null, null, null, 0, 25, B, "INSTRUCTOR"));
        assertThat(((ApplicationException) onA).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((ApplicationException) onA).getErrorCode()).isEqualTo(((ApplicationException) onMissing).getErrorCode());

        // A asks auth-service only for A's own member
        participants.getParticipants(new ParticipantDtos.Filter(), null, null, null, null, 0, 25, A, "INSTRUCTOR").block();
        ArgumentCaptor<ParticipantQuery.PageRequest> sent = ArgumentCaptor.forClass(ParticipantQuery.PageRequest.class);
        verify(directory).participants(sent.capture());
        assertThat(sent.getValue().getFilter().getIncludeIds()).containsExactly(MEMBER_OF_A);
    }
}
