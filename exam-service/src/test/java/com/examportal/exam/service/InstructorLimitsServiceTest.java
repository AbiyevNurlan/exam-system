package com.examportal.exam.service;

import com.examportal.common.domain.dto.instructor.InstructorDtos;
import com.examportal.common.exception.ApplicationException;
import com.examportal.exam.repository.InstructorLimitsRepository;
import com.examportal.exam.repository.InstructorLimitsRepository.Stored;
import com.examportal.exam.repository.InstructorLimitsRepository.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B13: a teacher's limits: the system default without own limits, the own limit when an administrator set one,
 * pending invites counted as members, 409 TEACHER_LIMIT_REACHED with which limit, its value and what is used, no
 * limit for ADMIN; setting limits writes an audit row with before and after.
 */
class InstructorLimitsServiceTest {

    private static final UUID TEACHER = UUID.randomUUID();
    private final InstructorLimitsRepository repository = mock(InstructorLimitsRepository.class);
    private final GroupAuditService audit = mock(GroupAuditService.class);
    private InstructorLimitsService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TransactionalOperator tx = mock(TransactionalOperator.class);
        when(tx.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
        service = new InstructorLimitsService(repository, audit, tx, 3, 10, 5, 1000L);
        when(repository.find(TEACHER)).thenReturn(Mono.empty());
        when(repository.usage(TEACHER)).thenReturn(Mono.just(new Usage(3, 8, 2, 4, 900)));
        when(audit.record(any(), anyString(), any(), any(), any())).thenReturn(Mono.empty());
        when(repository.save(eq(TEACHER), any(), any())).thenReturn(Mono.empty());
    }

    @SuppressWarnings("unchecked")
    private static void assertReached(Throwable error, String limit, long max, long used) {
        ApplicationException e = (ApplicationException) error;
        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getErrorCode()).isEqualTo("TEACHER_LIMIT_REACHED");
        assertThat((Map<String, Object>) e.getDetails()).containsEntry("limit", limit).containsEntry("max", max)
                .containsEntry("used", used);
    }

    @Test
    void the_system_default_applies_without_own_limits() {
        StepVerifier.create(service.requireRoom(TEACHER, "INSTRUCTOR", InstructorLimitsService.GROUPS, 1))
                .expectErrorSatisfies(e -> assertReached(e, "GROUPS", 3, 3)).verify();
        // members 8 + invites 2 = 10 of 10
        StepVerifier.create(service.requireRoom(TEACHER, "INSTRUCTOR", InstructorLimitsService.MEMBERS, 1))
                .expectErrorSatisfies(e -> assertReached(e, "MEMBERS", 10, 10)).verify();
        StepVerifier.create(service.requireRoom(TEACHER, "INSTRUCTOR", InstructorLimitsService.EXAMS, 1)).verifyComplete();
        StepVerifier.create(service.requireRoom(TEACHER, "INSTRUCTOR", InstructorLimitsService.MEDIA_BYTES, 101))
                .expectErrorSatisfies(e -> assertReached(e, "MEDIA_BYTES", 1000, 900)).verify();
        StepVerifier.create(service.requireRoom(TEACHER, "INSTRUCTOR", InstructorLimitsService.MEDIA_BYTES, 100)).verifyComplete();
    }

    @Test
    void an_own_limit_replaces_the_default() {
        when(repository.find(TEACHER)).thenReturn(Mono.just(new Stored(10, null, 4, null)));
        StepVerifier.create(service.requireRoom(TEACHER, "INSTRUCTOR", InstructorLimitsService.GROUPS, 1)).verifyComplete();
        StepVerifier.create(service.requireRoom(TEACHER, "INSTRUCTOR", InstructorLimitsService.EXAMS, 1))
                .expectErrorSatisfies(e -> assertReached(e, "EXAMS", 4, 4)).verify();
    }

    @Test
    void an_admin_has_no_limits_and_nothing_is_asked() {
        StepVerifier.create(service.requireRoom(UUID.randomUUID(), "ADMIN", InstructorLimitsService.GROUPS, 1000)).verifyComplete();
        verify(repository, never()).usage(any());
    }

    @Test
    void setting_limits_is_audited_with_before_and_after_and_answers_what_applies() {
        UUID admin = UUID.randomUUID();
        InstructorDtos.Limits answer = service.set(TEACHER, InstructorDtos.Limits.builder().maxGroups(20).build(), admin).block();
        verify(repository).save(eq(TEACHER), eq(new Stored(20, null, null, null)), eq(admin));
        verify(audit).record(eq(admin), eq(GroupAuditService.INSTRUCTOR_LIMITS_CHANGED), isNull(), eq(TEACHER),
                contains("after=groups:20,members:default"));
        assertThat(answer.getInstructorId()).isEqualTo(TEACHER);
        assertThat(answer.getUsedMembers()).isEqualTo(10);
    }

    @Test
    void the_answer_shows_the_limits_in_force_and_which_are_defaults() {
        when(repository.find(TEACHER)).thenReturn(Mono.just(new Stored(null, 50, null, null)));
        InstructorDtos.Limits limits = service.get(TEACHER).block();
        assertThat(limits.getMaxGroups()).isEqualTo(3);
        assertThat(limits.getMaxMembers()).isEqualTo(50);
        assertThat(limits.getDefaults()).containsExactly("GROUPS", "EXAMS", "MEDIA_BYTES");
        assertThat(limits.getUsedExams()).isEqualTo(4);
    }
}
