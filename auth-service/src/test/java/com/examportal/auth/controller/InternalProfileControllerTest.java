package com.examportal.auth.controller;

import com.examportal.auth.service.ParticipantProfileService;
import com.examportal.common.domain.dto.internal.ProfileWriteRequest;
import com.examportal.common.domain.dto.profile.ParticipantProfileDtos;
import com.examportal.common.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B11: the internal profile endpoints answer only with the shared secret (404 otherwise, like an unknown
 * path) and store only with a named actor.
 */
class InternalProfileControllerTest {

    private static final String SECRET = "x".repeat(40);
    private final ParticipantProfileService service = mock(ParticipantProfileService.class);
    private final InternalProfileController controller = new InternalProfileController(service);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "serviceSecret", SECRET);
    }

    @Test
    void withoutTheSecretEverythingIs404() {
        UUID user = UUID.randomUUID();
        for (String wrong : new String[]{null, "", "y".repeat(40)}) {
            StepVerifier.create(controller.get(wrong, user))
                    .expectErrorSatisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                    .verify();
            StepVerifier.create(controller.put(wrong, user, ProfileWriteRequest.builder().actorId(user).build()))
                    .expectErrorSatisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                    .verify();
        }
        verify(service, never()).mine(any());
        verify(service, never()).update(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void aWriteNeedsAnActor() {
        UUID user = UUID.randomUUID();
        StepVerifier.create(controller.put(SECRET, user, ProfileWriteRequest.builder().build())).expectError(ValidationException.class).verify();

        ParticipantProfileDtos.UpdateRequest profile = ParticipantProfileDtos.UpdateRequest.builder().category("OTHER").build();
        when(service.update(user, profile, user, false)).thenReturn(Mono.just(ParticipantProfileDtos.Profile.builder().category("OTHER").build()));
        StepVerifier.create(controller.put(SECRET, user, ProfileWriteRequest.builder().actorId(user).profile(profile).build()))
                .assertNext(answer -> assertThat(answer.getData().getCategory()).isEqualTo("OTHER"))
                .verifyComplete();

        // An administrator's PATCH arrives as partial with the administrator as actor
        UUID admin = UUID.randomUUID();
        when(service.update(user, profile, admin, true)).thenReturn(Mono.just(ParticipantProfileDtos.Profile.builder().category("OTHER").build()));
        StepVerifier.create(controller.put(SECRET, user, ProfileWriteRequest.builder().actorId(admin).profile(profile).partial(true).build()))
                .expectNextCount(1).verifyComplete();
        verify(service).update(user, profile, admin, true);
    }
}
