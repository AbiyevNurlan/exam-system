package com.examportal.exam.service;

import com.examportal.common.domain.dto.group.AddGroupMemberRequest;
import com.examportal.common.domain.dto.group.GroupRequest;
import com.examportal.common.domain.entity.UserGroup;
import com.examportal.common.exception.ApplicationException;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.client.UserDirectoryClient;
import com.examportal.exam.repository.GroupMembershipRepository;
import com.examportal.exam.repository.GroupOverviewRepository;
import com.examportal.exam.repository.PendingGroupInviteRepository;
import com.examportal.exam.repository.UserGroupMemberRepository;
import com.examportal.exam.repository.UserGroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * An INSTRUCTOR reaches only the own groups through GroupService; another owner's group answers
 * 404 GROUP_NOT_FOUND and nothing is written.
 */
class GroupServiceAccessTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final long GROUP_ID = 42L;

    private final UserGroupRepository groupRepository = mock(UserGroupRepository.class);
    private final UserGroupMemberRepository memberRepository = mock(UserGroupMemberRepository.class);
    private final PendingGroupInviteRepository inviteRepository = mock(PendingGroupInviteRepository.class);
    private final GroupAuditService auditService = mock(GroupAuditService.class);
    private final UserDirectoryClient userDirectory = mock(UserDirectoryClient.class);
    private final TransactionalOperator transactionalOperator = mock(TransactionalOperator.class);
    private final GroupOverviewRepository overviewRepository = mock(GroupOverviewRepository.class);
    private final GroupMembershipRepository membershipRepository = mock(GroupMembershipRepository.class);

    private GroupService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(transactionalOperator.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
        when(groupRepository.findActiveById(GROUP_ID)).thenReturn(Mono.just(
                UserGroup.builder().id(GROUP_ID).name("Owned").ownerId(OWNER).createdBy(OWNER).active(true).build()));
        service = new GroupService(groupRepository, memberRepository, inviteRepository, auditService, userDirectory,
                transactionalOperator, overviewRepository, membershipRepository, new GroupAccessPolicy(groupRepository));
    }

    private static UserPrincipal user(UUID id, String role) {
        return UserPrincipal.builder().id(id).role(role).build();
    }

    @Test
    void anotherInstructorCannotChangeTheGroup() {
        GroupRequest request = new GroupRequest();
        request.setName("Taken over");

        StepVerifier.create(service.updateGroup(GROUP_ID, request, user(OTHER, "INSTRUCTOR")))
                .expectErrorSatisfies(GroupServiceAccessTest::assertNotFound)
                .verify();
        verify(groupRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void anotherInstructorCannotDeleteOrAddMembers() {
        StepVerifier.create(service.deleteGroup(GROUP_ID, user(OTHER, "INSTRUCTOR")))
                .expectErrorSatisfies(GroupServiceAccessTest::assertNotFound)
                .verify();
        AddGroupMemberRequest add = new AddGroupMemberRequest();
        add.setUserId(UUID.randomUUID());
        StepVerifier.create(service.addMember(GROUP_ID, add, user(OTHER, "INSTRUCTOR")))
                .expectErrorSatisfies(GroupServiceAccessTest::assertNotFound)
                .verify();
        StepVerifier.create(service.removeMember(GROUP_ID, UUID.randomUUID(), user(OTHER, "INSTRUCTOR")))
                .expectErrorSatisfies(GroupServiceAccessTest::assertNotFound)
                .verify();

        verify(groupRepository, never()).softDelete(anyLong());
        verify(memberRepository, never()).addMember(anyLong(), any(), anyString(), any());
        verify(memberRepository, never()).removeMember(anyLong(), any());
        verifyNoInteractions(userDirectory, auditService);
    }

    @Test
    void anotherInstructorCannotReadMembersOrInvites() {
        StepVerifier.create(service.getMembers(GROUP_ID, 0, 25, user(OTHER, "INSTRUCTOR")))
                .expectErrorSatisfies(GroupServiceAccessTest::assertNotFound)
                .verify();
        StepVerifier.create(service.getInvites(GROUP_ID, 0, 25, user(OTHER, "INSTRUCTOR")))
                .expectErrorSatisfies(GroupServiceAccessTest::assertNotFound)
                .verify();
        StepVerifier.create(service.getGroup(GROUP_ID, user(OTHER, "INSTRUCTOR")))
                .expectErrorSatisfies(GroupServiceAccessTest::assertNotFound)
                .verify();
        verify(memberRepository, never()).countByGroupId(anyLong());
        verify(inviteRepository, never()).countByGroupId(anyLong());
        verify(overviewRepository, never()).findById(anyLong(), any());
    }

    @Test
    void theOwnerAndAnAdminCanChangeTheGroup() {
        when(groupRepository.save(any(UserGroup.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(auditService.record(any(), anyString(), any(), any(), any())).thenReturn(Mono.empty());
        GroupRequest request = new GroupRequest();
        request.setName("Renamed");

        StepVerifier.create(service.updateGroup(GROUP_ID, request, user(OWNER, "INSTRUCTOR")))
                .expectNextMatches(group -> "Renamed".equals(group.getName()) && OWNER.equals(group.getOwnerId()))
                .verifyComplete();
        StepVerifier.create(service.updateGroup(GROUP_ID, request, user(OTHER, "ADMIN")))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void aNewGroupBelongsToItsCreator() {
        when(groupRepository.save(any(UserGroup.class))).thenAnswer(call -> {
            UserGroup group = call.getArgument(0);
            group.setId(99L);
            return Mono.just(group);
        });
        when(auditService.record(any(), anyString(), any(), any(), any())).thenReturn(Mono.empty());
        GroupRequest request = new GroupRequest();
        request.setName("Mine");

        StepVerifier.create(service.createGroup(request, user(OTHER, "INSTRUCTOR")))
                .expectNextMatches(group -> OTHER.equals(group.getOwnerId()) && OTHER.equals(group.getCreatedBy()))
                .verifyComplete();
    }

    @Test
    void listsAreFilteredByOwnerInSql() {
        when(groupRepository.countByNamePattern(anyString(), any())).thenReturn(Mono.just(0L));
        when(groupRepository.findPageByNamePattern(anyString(), any(), anyInt(), anyLong())).thenReturn(Flux.empty());
        when(overviewRepository.findPage(anyString(), any(), anyInt(), anyLong())).thenReturn(Flux.empty());

        StepVerifier.create(service.listGroupNames(null, 0, 25, user(OWNER, "INSTRUCTOR"))).expectNextCount(1).verifyComplete();
        StepVerifier.create(service.searchGroups(null, 0, 25, user(OWNER, "INSTRUCTOR"))).expectNextCount(1).verifyComplete();
        StepVerifier.create(service.searchGroups(null, 0, 25, user(OTHER, "ADMIN"))).expectNextCount(1).verifyComplete();

        verify(groupRepository).findPageByNamePattern(anyString(), eq(OWNER), anyInt(), anyLong());
        verify(overviewRepository).findPage(anyString(), eq(OWNER), anyInt(), anyLong());
        verify(overviewRepository).findPage(anyString(), isNull(), anyInt(), anyLong());
    }

    private static void assertNotFound(Throwable error) {
        assertThat(error).isInstanceOf(ApplicationException.class);
        ApplicationException exception = (ApplicationException) error;
        assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exception.getErrorCode()).isEqualTo("GROUP_NOT_FOUND");
    }
}
