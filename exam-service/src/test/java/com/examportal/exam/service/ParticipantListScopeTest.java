package com.examportal.exam.service;

import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.group.ParticipantDtos;
import com.examportal.common.domain.dto.internal.ParticipantQuery;
import com.examportal.common.domain.entity.UserGroup;
import com.examportal.common.exception.ApplicationException;
import com.examportal.exam.client.UserDirectoryClient;
import com.examportal.exam.repository.GroupMembershipRepository;
import com.examportal.exam.repository.GroupMembershipRepository.UserGroupName;
import com.examportal.exam.repository.UserGroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B13: the participant list of a teacher holds only the members of the own groups (also with q or a 7-digit code,
 * which auth-service applies within those ids), never the lock state, never the name of another owner's group; the
 * profile filters, blocked and noGroup are 400; another owner's group is the same 404 as a missing one; a teacher
 * without members gets an empty page without asking auth-service.
 */
class ParticipantListScopeTest {

    private static final UUID TEACHER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();

    private final UserDirectoryClient userDirectory = mock(UserDirectoryClient.class);
    private final GroupMembershipRepository membershipRepository = mock(GroupMembershipRepository.class);
    private final UserGroupRepository groupRepository = mock(UserGroupRepository.class);
    private ParticipantService service;

    @BeforeEach
    void setUp() {
        service = new ParticipantService(userDirectory, membershipRepository, groupRepository, new ExamDayClock("Asia/Baku"));
        when(userDirectory.participants(any())).thenReturn(Mono.just(ParticipantQuery.PageResponse.builder()
                .users(List.of(ParticipantQuery.Participant.builder().id(MEMBER).firstName("Aysel").lastName("Quliyeva")
                        .participantCode(4141592).blocked(true).lockedUntil(LocalDateTime.now().plusHours(1)).build()))
                .total(1).build()));
        when(membershipRepository.findGroupsOfUsers(anyCollection(), eq(TEACHER)))
                .thenReturn(Flux.just(new UserGroupName(MEMBER, 1L, "11A")));
    }

    private Mono<PageResponse<ParticipantDtos.Participant>> list(ParticipantDtos.Filter filter, UUID caller, String role) {
        return service.getParticipants(filter, null, null, null, null, 0, 25, caller, role);
    }

    private static void assertCode(Throwable error, HttpStatus status, String code) {
        assertThat(error).isInstanceOf(ApplicationException.class);
        assertThat(((ApplicationException) error).getStatus()).isEqualTo(status);
        assertThat(((ApplicationException) error).getErrorCode()).isEqualTo(code);
    }

    @Test
    void only_the_members_of_the_own_groups_without_the_lock_state_and_other_groups() {
        when(membershipRepository.findMemberIdsOfOwner(TEACHER)).thenReturn(Mono.just(List.of(MEMBER)));

        PageResponse<ParticipantDtos.Participant> page = list(ParticipantDtos.Filter.builder().q("4141592").build(), TEACHER, "INSTRUCTOR").block();

        ArgumentCaptor<ParticipantQuery.PageRequest> sent = ArgumentCaptor.forClass(ParticipantQuery.PageRequest.class);
        verify(userDirectory).participants(sent.capture());
        assertThat(sent.getValue().getFilter().getIncludeIds()).containsExactly(MEMBER);
        assertThat(sent.getValue().getFilter().getQ()).isEqualTo("4141592");
        ParticipantDtos.Participant row = page.getContent().get(0);
        assertThat(row.isBlocked()).isFalse();
        assertThat(row.getLockedUntil()).isNull();
        assertThat(row.getGroups()).extracting(ParticipantDtos.GroupRef::getName).containsExactly("11A");
        verify(membershipRepository, never()).findGroupsOfUsers(anyCollection());
    }

    @Test
    void a_teacher_without_members_gets_an_empty_page_without_asking_auth_service() {
        when(membershipRepository.findMemberIdsOfOwner(TEACHER)).thenReturn(Mono.just(List.of()));
        PageResponse<ParticipantDtos.Participant> page = list(new ParticipantDtos.Filter(), TEACHER, "INSTRUCTOR").block();
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        verify(userDirectory, never()).participants(any());
    }

    @Test
    void profile_lock_and_no_group_filters_are_400_for_a_teacher() {
        when(membershipRepository.findMemberIdsOfOwner(TEACHER)).thenReturn(Mono.just(List.of(MEMBER)));
        for (ParticipantDtos.Filter filter : List.of(
                ParticipantDtos.Filter.builder().category("UNIVERSITY_STUDENT").build(),
                ParticipantDtos.Filter.builder().institution("BDU").build(),
                ParticipantDtos.Filter.builder().blocked(true).build(),
                ParticipantDtos.Filter.builder().noGroup(true).build())) {
            StepVerifier.create(list(filter, TEACHER, "INSTRUCTOR"))
                    .expectErrorSatisfies(e -> assertCode(e, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR"))
                    .verify();
        }
        verify(userDirectory, never()).participants(any());
    }

    @Test
    void another_owners_group_is_the_same_404_as_a_missing_group() {
        when(groupRepository.findActiveById(5L)).thenReturn(Mono.just(
                UserGroup.builder().id(5L).name("Foreign").ownerId(UUID.randomUUID()).active(true).build()));
        when(groupRepository.findActiveById(6L)).thenReturn(Mono.empty());
        when(membershipRepository.findMemberIdsOfOwner(TEACHER)).thenReturn(Mono.just(List.of(MEMBER)));
        for (long groupId : new long[]{5L, 6L}) {
            StepVerifier.create(list(ParticipantDtos.Filter.builder().groupId(groupId).build(), TEACHER, "INSTRUCTOR"))
                    .expectErrorSatisfies(e -> assertCode(e, HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND"))
                    .verify();
        }
        verify(userDirectory, never()).participants(any());
    }

    @Test
    void an_admin_sees_the_lock_state_and_every_group() {
        when(membershipRepository.findGroupsOfUsers(anyCollection(), eq(null)))
                .thenReturn(Flux.just(new UserGroupName(MEMBER, 1L, "11A"), new UserGroupName(MEMBER, 2L, "Other teacher's")));
        ParticipantDtos.Participant row = list(new ParticipantDtos.Filter(), UUID.randomUUID(), "ADMIN").block().getContent().get(0);
        assertThat(row.isBlocked()).isTrue();
        assertThat(row.getLockedUntil()).isNotNull();
        assertThat(row.getGroupCount()).isEqualTo(2);
    }
}
