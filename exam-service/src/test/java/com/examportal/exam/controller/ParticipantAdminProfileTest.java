package com.examportal.exam.controller;

import com.examportal.common.domain.dto.profile.ParticipantProfileDtos;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.client.UserDirectoryClient;
import com.examportal.exam.service.ParticipantService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B11: PATCH /api/admin/participants/{id}/profile is an administrator's (the controller is ADMIN only, and
 * /api/admin/** is ADMIN by path) and the administrator of the token is the actor of the audit row.
 */
class ParticipantAdminProfileTest {

    @Test
    void theAdministratorOfTheTokenIsTheActor() throws Exception {
        UserDirectoryClient directory = mock(UserDirectoryClient.class);
        ParticipantAdminController controller = new ParticipantAdminController(mock(ParticipantService.class), directory);
        UUID admin = UUID.randomUUID();
        UUID participant = UUID.randomUUID();
        UserPrincipal principal = mock(UserPrincipal.class);
        when(principal.getId()).thenReturn(admin);
        ParticipantProfileDtos.UpdateRequest change = ParticipantProfileDtos.UpdateRequest.builder().city("Gəncə").build();
        when(directory.patchProfile(eq(participant), eq(admin), any()))
                .thenReturn(Mono.just(ParticipantProfileDtos.Profile.builder().category("STUDENT").city("Gəncə").build()));

        assertThat(controller.patchProfile(participant, change, principal).block().getData().getCity()).isEqualTo("Gəncə");
        verify(directory).patchProfile(participant, admin, change);

        assertThat(ParticipantAdminController.class.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN')");
        assertThat(ParticipantAdminController.class.getMethod("patchProfile", UUID.class, ParticipantProfileDtos.UpdateRequest.class,
                UserPrincipal.class).getAnnotation(PatchMapping.class).value()).containsExactly("/{userId}/profile");
    }
}
