package com.examportal.exam.service;

import com.examportal.common.domain.dto.internal.UserLookupResponse.UserRef;
import com.examportal.common.domain.entity.GroupMemberSource;
import com.examportal.common.domain.entity.UserGroup;
import com.examportal.common.domain.entity.UserGroupMember;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.client.UserDirectoryClient;
import com.examportal.exam.repository.GroupMembershipRepository;
import com.examportal.exam.repository.GroupOverviewRepository;
import com.examportal.exam.repository.PendingGroupInviteRepository;
import com.examportal.exam.repository.UserGroupMemberRepository;
import com.examportal.exam.repository.UserGroupRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B10: the participant code that auth-service sends with a user (UserRef) reaches the answer next to
 * the name, and a user without one (an older auth-service) leaves it empty instead of failing.
 */
class ParticipantCodeDisplayTest {

    private static final UUID ADMIN = UUID.randomUUID();
    private static final long GROUP_ID = 5L;

    @Test
    void groupMembersCarryTheirParticipantCode() {
        UserGroupRepository groups = mock(UserGroupRepository.class);
        UserGroupMemberRepository members = mock(UserGroupMemberRepository.class);
        UserDirectoryClient directory = mock(UserDirectoryClient.class);
        UUID withCode = UUID.randomUUID();
        UUID withoutCode = UUID.randomUUID();
        when(groups.findActiveById(GROUP_ID)).thenReturn(Mono.just(
                UserGroup.builder().id(GROUP_ID).name("G").ownerId(ADMIN).createdBy(ADMIN).active(true).build()));
        when(members.countByGroupId(GROUP_ID)).thenReturn(Mono.just(2L));
        when(members.findPageByGroupId(GROUP_ID, 25, 0L)).thenReturn(Flux.just(
                UserGroupMember.builder().groupId(GROUP_ID).userId(withCode).source(GroupMemberSource.ADMIN).build(),
                UserGroupMember.builder().groupId(GROUP_ID).userId(withoutCode).source(GroupMemberSource.ADMIN).build()));
        when(directory.lookup(anyList(), anyList())).thenReturn(Mono.just(List.of(
                UserRef.builder().id(withCode).email("a@x.test").firstName("Ali").lastName("Veli").participantCode(4794913).build(),
                UserRef.builder().id(withoutCode).email("b@x.test").firstName("Aynur").lastName("Kerimova").build())));
        GroupService service = new GroupService(groups, members, mock(PendingGroupInviteRepository.class),
                mock(GroupAuditService.class), directory, mock(TransactionalOperator.class), mock(GroupOverviewRepository.class),
                mock(GroupMembershipRepository.class), new GroupAccessPolicy(groups));

        StepVerifier.create(service.getMembers(GROUP_ID, 0, 25, UserPrincipal.builder().id(ADMIN).role("ADMIN").build()))
                .assertNext(page -> {
                    assertThat(page.getContent()).hasSize(2);
                    assertThat(page.getContent().get(0).getParticipantCode()).isEqualTo(4794913);
                    assertThat(page.getContent().get(0).getFirstName()).isEqualTo("Ali");
                    assertThat(page.getContent().get(1).getParticipantCode()).isNull();
                })
                .verifyComplete();
    }
}
