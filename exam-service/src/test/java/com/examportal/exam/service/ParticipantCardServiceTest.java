package com.examportal.exam.service;

import com.examportal.common.domain.dto.AuditEntryResponse;
import com.examportal.common.domain.dto.MyStatsResponse;
import com.examportal.common.domain.dto.internal.AdminAuditQuery;
import com.examportal.common.domain.dto.participant.ParticipantCardDtos;
import com.examportal.common.domain.dto.profile.ParticipantProfileDtos;
import com.examportal.common.exception.ApplicationException;
import com.examportal.common.exception.ResourceNotFoundException;
import com.examportal.exam.client.UserDirectoryClient;
import com.examportal.exam.repository.AuditQueryRepository;
import com.examportal.exam.repository.MyStatsRepository;
import com.examportal.exam.repository.ParticipantCardRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B11: who sees which participant card. ADMIN: everything with events; INSTRUCTOR: a member of an own group
 * only, limited to the own groups and exams, no events; a participant outside the own groups is a 404 (and
 * auth-service is not even asked); STUDENT: 403. The card carries no secret field.
 */
class ParticipantCardServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID TEACHER = UUID.randomUUID();

    private final UserDirectoryClient directory = mock(UserDirectoryClient.class);
    private final ParticipantCardRepository cards = mock(ParticipantCardRepository.class);
    private final MyStatsRepository stats = mock(MyStatsRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final ParticipantCardService service = new ParticipantCardService(directory, cards, stats, audit);

    private final List<AuditQueryRepository.Row> examRows = List.of(new AuditQueryRepository.Row(1L, ADMIN, "MEMBER_ADDED", 4L, USER,
            "source=ADMIN", LocalDateTime.of(2026, 10, 16, 10, 0)));
    private final List<AdminAuditQuery.Entry> authRows = List.of(AdminAuditQuery.Entry.builder().id(UUID.randomUUID()).actorId(ADMIN)
            .action("PARTICIPANT_PROFILE_UPDATED").targetUserId(USER).details("city: - -> 'Bakı'").createdAt(LocalDateTime.of(2026, 10, 16, 11, 0)).build());

    @BeforeEach
    void setUp() {
        ParticipantCardDtos.AuthCard account = ParticipantCardDtos.AuthCard.builder().id(USER).participantCode(4141592)
                .firstName("Gülnarə").lastName("Məmmədova").email("p@x.test").role("STUDENT").emailVerified(true).active(true)
                .profile(ParticipantProfileDtos.Profile.builder().category("UNIVERSITY_STUDENT").institution("ADA")
                        .birthDate(java.time.LocalDate.of(2004, 5, 6)).workplace("ADA").build())
                .phoneNumber("+994501234567").blocked(true).lockedUntil(LocalDateTime.of(2030, 1, 1, 0, 0))
                .accountEvents(authRows).build();
        when(directory.participantCard(eq(USER), anyInt())).thenReturn(Mono.just(account));
        when(cards.findGroups(eq(USER), any())).thenReturn(Mono.just(List.of(ParticipantCardDtos.Membership.builder().groupId(4L).groupName("G").build())));
        when(cards.findSessions(eq(USER), any(), anyInt(), anyInt())).thenReturn(Mono.just(List.of()));
        when(cards.countSessions(eq(USER), any())).thenReturn(Mono.just(0L));
        when(cards.findEvents(eq(USER), anyInt())).thenReturn(Mono.just(examRows));
        when(stats.forUser(eq(USER), any(), any())).thenReturn(Mono.just(MyStatsResponse.builder().examsTaken(2).build()));
        when(audit.merge(anyList(), anyList(), anyInt())).thenReturn(Mono.just(List.of(AuditEntryResponse.builder().id("A-1")
                .action("PARTICIPANT_PROFILE_UPDATED").actorCode(1000001).targetUserCode(4141592).build())));
    }

    @Test
    void anAdministratorSeesEverythingWithTheEvents() {
        StepVerifier.create(service.card(USER, ADMIN, "ADMIN"))
                .assertNext(card -> {
                    assertThat(card.getParticipantCode()).isEqualTo(4141592);
                    assertThat(card.getScope()).isEqualTo(ParticipantCardDtos.SCOPE_ALL);
                    assertThat(card.getProfile().getInstitution()).isEqualTo("ADA");
                    assertThat(card.getProfile().getBirthDate()).isEqualTo(java.time.LocalDate.of(2004, 5, 6));
                    assertThat(card.getPhoneNumber()).isEqualTo("+994501234567");
                    assertThat(card.isBlocked()).isTrue();
                    assertThat(card.getGroups()).hasSize(1);
                    assertThat(card.getStats().getExamsTaken()).isEqualTo(2);
                    assertThat(card.getRecentEvents()).extracting(AuditEntryResponse::getActorCode).containsExactly(1000001);
                })
                .verifyComplete();
        verify(cards).findGroups(USER, null);
        verify(stats).forUser(eq(USER), isNull(), any());
        verify(directory).participantCard(USER, ParticipantCardDtos.EVENTS);
        verify(audit).merge(examRows, authRows, ParticipantCardDtos.EVENTS);
    }

    @Test
    void anInstructorSeesAMemberOfAnOwnGroupOnlyWithinTheOwnGroupsAndExams() {
        when(cards.isMemberOfGroupOwnedBy(USER, TEACHER)).thenReturn(Mono.just(true));
        StepVerifier.create(service.card(USER, TEACHER, "INSTRUCTOR"))
                .assertNext(card -> {
                    assertThat(card.getScope()).isEqualTo(ParticipantCardDtos.SCOPE_OWN);
                    assertThat(card.getRecentEvents()).isEmpty();
                    // B13: name, code and email; never the profile, the phone or the lock state
                    assertThat(card.getParticipantCode()).isEqualTo(4141592);
                    assertThat(card.getEmail()).isEqualTo("p@x.test");
                    assertThat(card.getFirstName()).isNotBlank();
                    assertThat(card.getProfile()).isNull();
                    assertThat(card.getPhoneNumber()).isNull();
                    assertThat(card.isBlocked()).isFalse();
                    assertThat(card.getLockedUntil()).isNull();
                })
                .verifyComplete();
        verify(cards).findGroups(USER, TEACHER);
        verify(cards).findSessions(USER, TEACHER, 0, ParticipantCardDtos.DEFAULT_SESSIONS);
        verify(stats).forUser(eq(USER), eq(TEACHER), any());
        verify(directory).participantCard(USER, 0);
        verify(cards, never()).findEvents(any(), anyInt());
        verify(audit, never()).merge(anyList(), anyList(), anyInt());
    }

    @Test
    void anyoneElseOfAnInstructorIsA404AndNothingIsAsked() {
        when(cards.isMemberOfGroupOwnedBy(USER, TEACHER)).thenReturn(Mono.just(false));
        StepVerifier.create(service.card(USER, TEACHER, "INSTRUCTOR")).expectError(ResourceNotFoundException.class).verify();
        StepVerifier.create(service.sessionsPage(USER, TEACHER, "INSTRUCTOR", 0, 20)).expectError(ResourceNotFoundException.class).verify();
        verify(directory, never()).participantCard(any(), anyInt());
        verify(cards, never()).findSessions(any(), any(), anyInt(), anyInt());
    }

    @Test
    void aParticipantIsRefused() {
        StepVerifier.create(service.card(USER, UUID.randomUUID(), "STUDENT"))
                .expectErrorSatisfies(e -> assertThat(((ApplicationException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
        StepVerifier.create(service.card(USER, null, null))
                .expectErrorSatisfies(e -> assertThat(((ApplicationException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN))
                .verify();
        verify(directory, never()).participantCard(any(), anyInt());
    }

    @Test
    void anUnknownIdIs404() {
        UUID nobody = UUID.randomUUID();
        when(directory.participantCard(eq(nobody), anyInt())).thenReturn(Mono.error(new ResourceNotFoundException("User not found")));
        when(cards.findGroups(eq(nobody), any())).thenReturn(Mono.just(List.of()));
        when(cards.findSessions(eq(nobody), any(), anyInt(), anyInt())).thenReturn(Mono.just(List.of()));
        when(cards.countSessions(eq(nobody), any())).thenReturn(Mono.just(0L));
        when(cards.findEvents(eq(nobody), anyInt())).thenReturn(Mono.just(List.of()));
        when(stats.forUser(eq(nobody), any(), any())).thenReturn(Mono.just(MyStatsResponse.builder().build()));
        StepVerifier.create(service.card(nobody, ADMIN, "ADMIN")).expectError(ResourceNotFoundException.class).verify();
    }

    @Test
    void sessionPagesAreCapped() {
        StepVerifier.create(service.sessionsPage(USER, ADMIN, "ADMIN", -3, 500))
                .assertNext(page -> {
                    assertThat(page.getPage()).isZero();
                    assertThat(page.getSize()).isEqualTo(ParticipantCardDtos.MAX_SESSIONS);
                })
                .verifyComplete();
        verify(cards).findSessions(USER, null, 0, ParticipantCardDtos.MAX_SESSIONS);
    }

    @Test
    void theCardCarriesNoSecretField() throws Exception {
        ParticipantCardDtos.Card card = service.card(USER, ADMIN, "ADMIN").block();
        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(card).toLowerCase(Locale.ROOT);
        for (String secret : List.of("password", "hash", "token", "otp", "secret", "failedlogin", "refresh")) {
            assertThat(json).as(secret).doesNotContain("\"" + secret).doesNotContain(secret + "\":");
        }
        String accountJson = new ObjectMapper().registerModule(new JavaTimeModule())
                .writeValueAsString(ParticipantCardDtos.AuthCard.builder().build()).toLowerCase(Locale.ROOT);
        assertThat(accountJson).doesNotContain("password").doesNotContain("hash").doesNotContain("token");
    }

    @Test
    void onlyAUserIdPathIsOpenToInstructors() {
        PathPattern card = PathPatternParser.defaultInstance.parse("/api/admin/participants/{userId:"
                + com.examportal.exam.config.SecurityConfig.UUID_PATTERN + "}");
        assertThat(card.matches(PathContainer.parsePath("/api/admin/participants/" + USER))).isTrue();
        assertThat(card.matches(PathContainer.parsePath("/api/admin/participants/summary"))).isFalse();
        assertThat(card.matches(PathContainer.parsePath("/api/admin/participants/" + USER + "/profile"))).isFalse();
        assertThat(card.matches(PathContainer.parsePath("/api/admin/participants/not-a-uuid"))).isFalse();
    }
}
