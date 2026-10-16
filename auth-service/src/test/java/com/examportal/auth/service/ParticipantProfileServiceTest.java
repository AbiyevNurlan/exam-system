package com.examportal.auth.service;

import com.examportal.auth.config.ParticipantProfileProperties;
import com.examportal.auth.repository.ParticipantProfileRepository;
import com.examportal.auth.repository.UserRepository;
import com.examportal.common.domain.dto.profile.ParticipantProfileDtos;
import com.examportal.common.domain.dto.profile.ParticipantProfileDtos.UpdateRequest;
import com.examportal.common.domain.entity.User;
import com.examportal.common.domain.entity.UserRole;
import com.examportal.common.exception.ApplicationException;
import com.examportal.common.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B11: the participant profile service. A change is checked before any lock, writes one upsert and one
 * audit row with before and after (own change and an administrator's change are told apart), a change
 * that changes nothing writes nothing, and an unknown account is a 404.
 */
class ParticipantProfileServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();

    private final ParticipantProfileRepository profiles = mock(ParticipantProfileRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AccountAuditLog audit = mock(AccountAuditLog.class);
    private final TransactionalOperator tx = mock(TransactionalOperator.class);
    private final ParticipantProfileProperties properties = new ParticipantProfileProperties();
    private ParticipantProfileService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(tx.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
        User user = User.builder().email("p@x.test").firstName("A").lastName("B").role(UserRole.STUDENT).build();
        user.setId(USER);
        user.setActive(true);
        when(users.findById(USER)).thenReturn(Mono.just(user));
        when(users.findByIdForUpdate(USER)).thenReturn(Mono.just(user));
        when(profiles.activeCategories()).thenReturn(Mono.just(List.of("STUDENT", "UNIVERSITY_STUDENT", "INTERN", "OTHER")));
        when(profiles.upsert(eq(USER), any(), any())).thenReturn(Mono.empty());
        when(audit.record(any(UUID.class), anyString(), any(UUID.class), anyString())).thenReturn(Mono.empty());
        service = new ParticipantProfileService(profiles, users, audit, properties, tx);
    }

    @Test
    void anAccountWithoutAProfileHasNoneAndThatIsNotAnError() {
        when(profiles.findByUserId(USER)).thenReturn(Mono.empty());
        StepVerifier.create(service.get(USER)).assertNext(found -> assertThat(found).isEmpty()).verifyComplete();
        StepVerifier.create(service.mine(USER)).assertNext(mine -> {
            assertThat(mine.getProfile()).isNull();
            assertThat(mine.getCategories()).contains("UNIVERSITY_STUDENT");
            assertThat(mine.getRequiredFields()).isEmpty();
        }).verifyComplete();
    }

    @Test
    void theOwnFirstProfileIsStoredCleanedAndAudited() {
        ParticipantProfileDtos.Profile stored = ParticipantProfileDtos.Profile.builder().category("UNIVERSITY_STUDENT").institution("ADA").build();
        when(profiles.findByUserId(USER)).thenReturn(Mono.empty(), Mono.just(stored));

        StepVerifier.create(service.update(USER, UpdateRequest.builder().category("university_student")
                        .institution("  <b>ADA</b> ").build(), USER))
                .assertNext(profile -> assertThat(profile.getInstitution()).isEqualTo("ADA"))
                .verifyComplete();

        ArgumentCaptor<UpdateRequest> written = ArgumentCaptor.forClass(UpdateRequest.class);
        verify(profiles).upsert(eq(USER), written.capture(), eq(USER));
        assertThat(written.getValue().getCategory()).isEqualTo("UNIVERSITY_STUDENT");
        assertThat(written.getValue().getInstitution()).isEqualTo("ADA");
        verify(audit).record(USER, AccountAuditLog.PROFILE_DETAILS_UPDATED, USER,
                "category: - -> 'UNIVERSITY_STUDENT'; institution: - -> 'ADA'");
    }

    @Test
    void anAdministratorsChangeIsAuditedAsSuchWithBeforeAndAfter() {
        ParticipantProfileDtos.Profile before = ParticipantProfileDtos.Profile.builder().category("STUDENT").city("Bakı").build();
        when(profiles.findByUserId(USER)).thenReturn(Mono.just(before), Mono.just(before));

        StepVerifier.create(service.update(USER, UpdateRequest.builder().category("INTERN").city("Gəncə").build(), ADMIN))
                .expectNextCount(1).verifyComplete();

        verify(profiles).upsert(eq(USER), any(), eq(ADMIN));
        verify(audit).record(ADMIN, AccountAuditLog.PARTICIPANT_PROFILE_UPDATED, USER,
                "category: 'STUDENT' -> 'INTERN'; city: 'Bakı' -> 'Gəncə'");
    }

    @Test
    void aChangeThatChangesNothingWritesNothing() {
        ParticipantProfileDtos.Profile before = ParticipantProfileDtos.Profile.builder().category("STUDENT").city("Bakı").build();
        when(profiles.findByUserId(USER)).thenReturn(Mono.just(before));

        StepVerifier.create(service.update(USER, UpdateRequest.builder().category("STUDENT").city(" Bakı ").build(), USER))
                .assertNext(profile -> assertThat(profile).isSameAs(before)).verifyComplete();
        verify(profiles, never()).upsert(any(), any(), any());
        verify(audit, never()).record(any(UUID.class), anyString(), any(UUID.class), anyString());
    }

    @Test
    void anInvalidRequestIsRefusedBeforeTheAccountIsLocked() {
        StepVerifier.create(service.update(USER, UpdateRequest.builder().category("ALIEN").build(), USER))
                .expectError(ValidationException.class).verify();
        properties.setRequiredFields(Map.of("UNIVERSITY_STUDENT", List.of("institution")));
        StepVerifier.create(service.update(USER, UpdateRequest.builder().category("UNIVERSITY_STUDENT").build(), USER))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ValidationException.class).hasMessageContaining("institution"))
                .verify();
        verify(users, never()).findByIdForUpdate(any());
        verify(profiles, never()).upsert(any(), any(), any());
    }

    @Test
    void anAdministratorsPatchKeepsWhatIsLeftOutAndClearsWhatIsEmpty() {
        ParticipantProfileDtos.Profile before = ParticipantProfileDtos.Profile.builder().category("UNIVERSITY_STUDENT")
                .institution("ADA").city("Bakı").courseOrGrade("2").build();
        when(profiles.findByUserId(USER)).thenReturn(Mono.just(before), Mono.just(before));

        StepVerifier.create(service.update(USER, UpdateRequest.builder().courseOrGrade("3").city("").build(), ADMIN, true))
                .expectNextCount(1).verifyComplete();

        ArgumentCaptor<UpdateRequest> written = ArgumentCaptor.forClass(UpdateRequest.class);
        verify(profiles).upsert(eq(USER), written.capture(), eq(ADMIN));
        assertThat(written.getValue().getCategory()).isEqualTo("UNIVERSITY_STUDENT");
        assertThat(written.getValue().getInstitution()).isEqualTo("ADA");
        assertThat(written.getValue().getCourseOrGrade()).isEqualTo("3");
        assertThat(written.getValue().getCity()).isNull();
        verify(audit).record(ADMIN, AccountAuditLog.PARTICIPANT_PROFILE_UPDATED, USER,
                "courseOrGrade: '2' -> '3'; city: 'Bakı' -> -");
    }

    @Test
    void aPatchWithoutAStoredProfileNeedsTheCategory() {
        when(profiles.findByUserId(USER)).thenReturn(Mono.empty());
        StepVerifier.create(service.update(USER, UpdateRequest.builder().city("Bakı").build(), ADMIN, true))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ValidationException.class).hasMessageContaining("category"))
                .verify();
        verify(profiles, never()).upsert(any(), any(), any());
    }

    @Test
    void anUnknownOrInactiveAccountIs404() {
        UUID nobody = UUID.randomUUID();
        when(users.findById(nobody)).thenReturn(Mono.empty());
        when(users.findByIdForUpdate(nobody)).thenReturn(Mono.empty());
        StepVerifier.create(service.get(nobody))
                .expectErrorSatisfies(e -> assertThat(((ApplicationException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
        StepVerifier.create(service.update(nobody, UpdateRequest.builder().category("OTHER").build(), ADMIN))
                .expectErrorSatisfies(e -> assertThat(((ApplicationException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
        verify(profiles, never()).upsert(any(), any(), any());
    }
}
