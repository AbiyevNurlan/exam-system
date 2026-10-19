package com.examportal.exam.service;

import com.examportal.common.domain.dto.group.AddGroupMemberRequest;
import com.examportal.common.domain.dto.group.AddGroupMemberResponse;
import com.examportal.common.domain.dto.internal.ParticipantQuery;
import com.examportal.common.domain.dto.internal.UserLookupResponse.UserRef;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B13: adding one member must not tell a teacher whether an account exists. By email a teacher's request never
 * looks the account up (always a pending invite, the same answer for a known and an unknown address); by id only a
 * member of one of the teacher's groups (anybody else is the same 404 as an unknown id); by participant code an
 * exact match with a masked name, an unknown code 404, at most 10 a minute. ADMIN keeps the lookup by email.
 */
class GroupMemberAddTest {

    private static final UUID TEACHER = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID KNOWN = UUID.randomUUID();
    private static final long GROUP_ID = 7L;

    private final UserGroupRepository groupRepository = mock(UserGroupRepository.class);
    private final UserGroupMemberRepository memberRepository = mock(UserGroupMemberRepository.class);
    private final PendingGroupInviteRepository inviteRepository = mock(PendingGroupInviteRepository.class);
    private final GroupAuditService auditService = mock(GroupAuditService.class);
    private final UserDirectoryClient userDirectory = mock(UserDirectoryClient.class);
    private final TransactionalOperator transactionalOperator = mock(TransactionalOperator.class);
    private final GroupMembershipRepository membershipRepository = mock(GroupMembershipRepository.class);
    private GroupService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(transactionalOperator.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
        when(groupRepository.findActiveById(GROUP_ID)).thenReturn(Mono.just(
                UserGroup.builder().id(GROUP_ID).name("11A").ownerId(TEACHER).createdBy(TEACHER).active(true).build()));
        when(inviteRepository.addInvite(eq(GROUP_ID), anyString(), any())).thenReturn(Mono.just(1));
        when(inviteRepository.removeInvite(eq(GROUP_ID), anyString())).thenReturn(Mono.just(0));
        when(memberRepository.addMember(eq(GROUP_ID), any(), anyString(), any())).thenReturn(Mono.just(1));
        when(auditService.record(any(), anyString(), any(), any(), any())).thenReturn(Mono.empty());
        when(userDirectory.findByEmail(anyString())).thenReturn(Mono.just(
                UserRef.builder().id(KNOWN).email("known@x.test").emailVerified(true).build()));
        service = new GroupService(groupRepository, memberRepository, inviteRepository, auditService, userDirectory,
                transactionalOperator, mock(GroupOverviewRepository.class), membershipRepository,
                new GroupAccessPolicy(groupRepository), new ActionRateLimiter(mock(ObjectProvider.class)));
    }

    private static UserPrincipal teacher() {
        return UserPrincipal.builder().id(TEACHER).role("INSTRUCTOR").build();
    }

    private static AddGroupMemberRequest byEmail(String email) {
        return AddGroupMemberRequest.builder().email(email).build();
    }

    private static AddGroupMemberRequest byCode(int code) {
        return AddGroupMemberRequest.builder().participantCode(code).build();
    }

    private static void assertCode(Throwable error, HttpStatus status, String code) {
        assertThat(error).isInstanceOf(ApplicationException.class);
        assertThat(((ApplicationException) error).getStatus()).isEqualTo(status);
        assertThat(((ApplicationException) error).getErrorCode()).isEqualTo(code);
    }

    @Test
    void a_teacher_adding_by_email_gets_the_same_invite_for_a_known_and_an_unknown_address() {
        AddGroupMemberResponse known = service.addMember(GROUP_ID, byEmail("known@x.test"), teacher()).block();
        AddGroupMemberResponse unknown = service.addMember(GROUP_ID, byEmail("nobody@x.test"), teacher()).block();

        assertThat(known).isEqualTo(unknown);
        assertThat(known.getStatus()).isEqualTo(AddGroupMemberResponse.Status.INVITED);
        assertThat(known.getUserId()).isNull();
        verify(userDirectory, never()).findByEmail(anyString());
        verify(memberRepository, never()).addMember(anyLong(), any(), anyString(), any());
    }

    @Test
    void an_admin_still_adds_a_verified_account_by_email_at_once() {
        AddGroupMemberResponse added = service.addMember(GROUP_ID, byEmail("known@x.test"),
                UserPrincipal.builder().id(ADMIN).role("ADMIN").build()).block();
        assertThat(added.getStatus()).isEqualTo(AddGroupMemberResponse.Status.ADDED);
        assertThat(added.getUserId()).isEqualTo(KNOWN);
    }

    @Test
    void a_teacher_adds_by_id_only_someone_of_the_own_groups() {
        UUID stranger = UUID.randomUUID();
        when(membershipRepository.isMemberOfOwner(stranger, TEACHER)).thenReturn(Mono.just(false));
        when(membershipRepository.isMemberOfOwner(KNOWN, TEACHER)).thenReturn(Mono.just(true));
        when(userDirectory.findById(KNOWN)).thenReturn(Mono.just(UserRef.builder().id(KNOWN).build()));

        StepVerifier.create(service.addMember(GROUP_ID, AddGroupMemberRequest.builder().userId(stranger).build(), teacher()))
                .expectErrorSatisfies(e -> assertCode(e, HttpStatus.NOT_FOUND, "USER_NOT_FOUND"))
                .verify();
        verify(userDirectory, never()).findById(stranger);
        assertThat(service.addMember(GROUP_ID, AddGroupMemberRequest.builder().userId(KNOWN).build(), teacher()).block()
                .getStatus()).isEqualTo(AddGroupMemberResponse.Status.ADDED);
    }

    @Test
    void by_code_an_exact_match_with_a_masked_name_and_an_unknown_code_is_404() {
        UUID aysel = UUID.randomUUID();
        when(userDirectory.participants(any())).thenAnswer(call -> {
            ParticipantQuery.PageRequest request = call.getArgument(0);
            // q matches names and email prefixes too: one row with another code must not count
            List<ParticipantQuery.Participant> rows = "4141592".equals(request.getFilter().getQ())
                    ? List.of(ParticipantQuery.Participant.builder().id(UUID.randomUUID()).participantCode(1111111).build(),
                    ParticipantQuery.Participant.builder().id(aysel).participantCode(4141592)
                            .firstName("Aysel").lastName("Quliyeva").build())
                    : List.of(ParticipantQuery.Participant.builder().id(UUID.randomUUID()).participantCode(1111111).build());
            assertThat(request.getFilter().getVerified()).isTrue();
            return Mono.just(ParticipantQuery.PageResponse.builder().users(rows).total(rows.size()).build());
        });

        AddGroupMemberResponse added = service.addMember(GROUP_ID, byCode(4141592), teacher()).block();
        assertThat(added.getStatus()).isEqualTo(AddGroupMemberResponse.Status.ADDED);
        assertThat(added.getUserId()).isEqualTo(aysel);
        assertThat(added.getMaskedName()).isEqualTo("A*** Q***");

        StepVerifier.create(service.addMember(GROUP_ID, byCode(2222222), teacher()))
                .expectErrorSatisfies(e -> assertCode(e, HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND"))
                .verify();
    }

    @Test
    void by_code_at_most_ten_a_minute() {
        when(userDirectory.participants(any())).thenReturn(Mono.just(
                ParticipantQuery.PageResponse.builder().users(List.of()).total(0).build()));
        for (int i = 0; i < GroupService.CODE_ADDITIONS_PER_MINUTE; i++) {
            StepVerifier.create(service.addMember(GROUP_ID, byCode(3000000 + i), teacher()))
                    .expectErrorSatisfies(e -> assertCode(e, HttpStatus.NOT_FOUND, "PARTICIPANT_NOT_FOUND"))
                    .verify();
        }
        StepVerifier.create(service.addMember(GROUP_ID, byCode(3999999), teacher()))
                .expectErrorSatisfies(e -> assertCode(e, HttpStatus.TOO_MANY_REQUESTS, "MEMBER_CODE_RATE_LIMITED"))
                .verify();
    }

    @Test
    void exactly_one_reference() {
        StepVerifier.create(service.addMember(GROUP_ID, AddGroupMemberRequest.builder().email("a@x.test").participantCode(4141592).build(), teacher()))
                .expectErrorSatisfies(e -> assertCode(e, HttpStatus.BAD_REQUEST, "GROUP_MEMBER_REFERENCE_REQUIRED"))
                .verify();
    }

    @Test
    void the_masked_name() {
        assertThat(GroupService.maskName("Şəkər", "Əliyeva")).isEqualTo("Ş*** Ə***");
        assertThat(GroupService.maskName(" Aysel ", null)).isEqualTo("A***");
    }
}
