package com.examportal.exam.service;

import com.examportal.common.domain.dto.internal.UserLookupResponse.UserRef;
import com.examportal.common.exception.ApplicationException;
import com.examportal.exam.client.UserDirectoryClient;
import com.examportal.exam.repository.GroupJoinRepository;
import com.examportal.exam.repository.GroupJoinRepository.Link;
import com.examportal.exam.repository.GroupJoinRepository.OwnRequest;
import com.examportal.exam.repository.UserGroupMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Join requests: the rejection cooldown and the participant's own list
 */
class GroupJoinServiceTest {

    private static final String CODE = "ABCDEFGHJK";
    private static final long GROUP_ID = 5L;
    private static final UUID USER = UUID.randomUUID();

    private final GroupJoinRepository joinRepository = mock(GroupJoinRepository.class);
    private final UserGroupMemberRepository memberRepository = mock(UserGroupMemberRepository.class);
    private final GroupAuditService auditService = mock(GroupAuditService.class);
    private final UserDirectoryClient userDirectory = mock(UserDirectoryClient.class);
    private final ParticipantDirectory participants = mock(ParticipantDirectory.class);
    private final TransactionalOperator transactionalOperator = mock(TransactionalOperator.class);
    private final GroupAccessPolicy accessPolicy = mock(GroupAccessPolicy.class);

    private GroupJoinService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(transactionalOperator.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
        when(userDirectory.findById(USER)).thenReturn(Mono.just(UserRef.builder().id(USER).emailVerified(true).build()));
        when(joinRepository.lockByCode(CODE)).thenReturn(Mono.just(new Link(1L, GROUP_ID, CODE, true, true, null, null, 0,
                UUID.randomUUID(), LocalDateTime.now(), LocalDateTime.now(), "Group")));
        when(joinRepository.isMember(GROUP_ID, USER)).thenReturn(Mono.just(false));
        when(joinRepository.hasPendingRequest(GROUP_ID, USER)).thenReturn(Mono.just(false));
        when(joinRepository.addRequest(eq(GROUP_ID), eq(USER), any())).thenReturn(Mono.just(1L));
        when(joinRepository.countUse(anyLong(), any())).thenReturn(Mono.just(1L));
        when(auditService.record(any(), anyString(), any(), any(), any())).thenReturn(Mono.empty());
        service = new GroupJoinService(memberRepository, joinRepository, auditService, userDirectory, participants,
                transactionalOperator, accessPolicy);
    }

    @Test
    void aRecentRejectionBlocksANewRequest() {
        LocalDateTime rejected = LocalDateTime.now().minusHours(2);
        when(joinRepository.lastRejectedAt(GROUP_ID, USER)).thenReturn(Mono.just(rejected));

        StepVerifier.create(service.join(CODE, USER, "STUDENT"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ApplicationException.class);
                    ApplicationException exception = (ApplicationException) error;
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getErrorCode()).isEqualTo("JOIN_REQUEST_COOLDOWN");
                    assertThat(((Map<?, ?>) exception.getDetails()).get("canRequestAgainAt")).isEqualTo(rejected.plusHours(24));
                })
                .verify();
        verify(joinRepository, never()).addRequest(anyLong(), any(), any());
        verify(joinRepository, never()).countUse(anyLong(), any());
    }

    @Test
    void afterTheCooldownTheUserMayAskAgain() {
        when(joinRepository.lastRejectedAt(GROUP_ID, USER)).thenReturn(Mono.just(LocalDateTime.now().minusHours(25)));

        StepVerifier.create(service.join(CODE, USER, "STUDENT"))
                .expectNextMatches(result -> "PENDING".equals(result.getStatus()) && result.isChanged())
                .verifyComplete();
        verify(joinRepository).addRequest(eq(GROUP_ID), eq(USER), any());
    }

    @Test
    void neverRejectedMeansNoWait() {
        when(joinRepository.lastRejectedAt(GROUP_ID, USER)).thenReturn(Mono.empty());

        StepVerifier.create(service.join(CODE, USER, "STUDENT"))
                .expectNextMatches(result -> "PENDING".equals(result.getStatus()))
                .verifyComplete();
    }

    @Test
    void theCooldownCanBeSwitchedOff() {
        ReflectionTestUtils.setField(service, "rejectCooldownHours", 0L);
        when(joinRepository.lastRejectedAt(GROUP_ID, USER)).thenReturn(Mono.just(LocalDateTime.now().minusMinutes(1)));

        StepVerifier.create(service.join(CODE, USER, "STUDENT"))
                .expectNextMatches(result -> "PENDING".equals(result.getStatus()))
                .verifyComplete();
        verify(joinRepository, never()).lastRejectedAt(anyLong(), any());
    }

    @Test
    void theParticipantSeesTheDecisionAndWhenToAskAgain() {
        LocalDateTime now = LocalDateTime.now();
        when(joinRepository.countRequestsOfUser(USER)).thenReturn(Mono.just(4L));
        when(joinRepository.findRequestsOfUser(eq(USER), anyInt(), anyLong())).thenReturn(Flux.just(
                // newest first: group 5 rejected an hour ago, earlier rejected long ago; group 6 approved; group 7 open
                new OwnRequest(14L, GROUP_ID, "Group", "REJECTED", now.minusHours(3), now.minusHours(1)),
                new OwnRequest(13L, 6L, "Other", "APPROVED", now.minusDays(1), now.minusHours(20)),
                new OwnRequest(12L, 7L, "Third", "PENDING", now.minusDays(2), null),
                new OwnRequest(11L, GROUP_ID, "Group", "REJECTED", now.minusDays(5), now.minusDays(4))));

        StepVerifier.create(service.myRequests(USER, 0, 20))
                .assertNext(page -> {
                    assertThat(page.getContent()).hasSize(4);
                    assertThat(page.getContent().get(0).getStatus()).isEqualTo("REJECTED");
                    assertThat(page.getContent().get(0).getDecidedAt()).isEqualTo(now.minusHours(1));
                    assertThat(page.getContent().get(0).getCanRequestAgainAt()).isEqualTo(now.minusHours(1).plusHours(24));
                    assertThat(page.getContent().get(1).getStatus()).isEqualTo("APPROVED");
                    assertThat(page.getContent().get(1).getCanRequestAgainAt()).isNull();
                    assertThat(page.getContent().get(2).getCanRequestAgainAt()).isNull();
                    // an older rejection of the same group says nothing about asking again
                    assertThat(page.getContent().get(3).getCanRequestAgainAt()).isNull();
                })
                .verifyComplete();
    }
}
