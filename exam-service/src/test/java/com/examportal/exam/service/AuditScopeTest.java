package com.examportal.exam.service;

import com.examportal.common.domain.dto.AuditEntryResponse;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.internal.AdminAuditQuery;
import com.examportal.exam.client.UserDirectoryClient;
import com.examportal.exam.repository.AuditQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B13: a teacher's audit list holds only the teacher's own actions in exam-service, never the admin log of
 * auth-service, with any email in the details masked; another actor or source AUTH_SERVICE is an empty page.
 * ADMIN keeps both logs.
 */
class AuditScopeTest {

    private static final UUID TEACHER = UUID.randomUUID();

    private final AuditQueryRepository repository = mock(AuditQueryRepository.class);
    private final UserDirectoryClient userDirectory = mock(UserDirectoryClient.class);
    private final ParticipantDirectory participants = mock(ParticipantDirectory.class);
    private AuditService service;

    @BeforeEach
    void setUp() {
        service = new AuditService(repository, userDirectory, participants);
        when(repository.findNewest(any(), anyInt())).thenReturn(Flux.just(new AuditQueryRepository.Row(1L, TEACHER,
                "INVITE_CREATED", 4L, null, "email=aysel.quliyeva@example.com code=123456", LocalDateTime.now())));
        when(repository.count(any())).thenReturn(Mono.just(1L));
        when(participants.names(anyCollection())).thenReturn(Mono.just(Map.of()));
        when(userDirectory.adminAudit(any())).thenReturn(Mono.just(AdminAuditQuery.Response.builder().total(0).entries(List.of()).build()));
    }

    @Test
    void a_teacher_sees_only_the_own_actions_of_exam_service_with_emails_masked() {
        PageResponse<AuditEntryResponse> page = service.list(null, null, null, null, null, null, 0, 25, TEACHER, "INSTRUCTOR").block();

        ArgumentCaptor<AuditQueryRepository.Filter> filter = ArgumentCaptor.forClass(AuditQueryRepository.Filter.class);
        verify(repository).findNewest(filter.capture(), anyInt());
        assertThat(filter.getValue().actorId()).isEqualTo(TEACHER);
        verify(userDirectory, never()).adminAudit(any());
        String details = page.getContent().get(0).getDetails();
        assertThat(details).doesNotContain("aysel.quliyeva@example.com").doesNotContain("123456");
    }

    @Test
    void another_actor_or_the_auth_log_is_an_empty_page_for_a_teacher() {
        assertThat(service.list(null, UUID.randomUUID().toString(), null, null, null, null, 0, 25, TEACHER, "INSTRUCTOR")
                .block().getTotalElements()).isZero();
        assertThat(service.list(null, null, null, null, null, "AUTH_SERVICE", 0, 25, TEACHER, "INSTRUCTOR")
                .block().getTotalElements()).isZero();
        verify(repository, never()).findNewest(any(), anyInt());
        verify(userDirectory, never()).adminAudit(any());
    }

    @Test
    void an_admin_keeps_both_logs_and_every_actor() {
        service.list(null, null, null, null, null, null, 0, 25, UUID.randomUUID(), "ADMIN").block();
        ArgumentCaptor<AuditQueryRepository.Filter> filter = ArgumentCaptor.forClass(AuditQueryRepository.Filter.class);
        verify(repository).findNewest(filter.capture(), anyInt());
        assertThat(filter.getValue().actorId()).isNull();
        verify(userDirectory).adminAudit(any());
    }

    @Test
    void emails_are_masked() {
        assertThat(AuditService.maskEmails("from a.b@x.test to c@y.az")).doesNotContain("a.b@x.test").doesNotContain("c@y.az");
        assertThat(AuditService.maskEmails(null)).isNull();
    }
}
