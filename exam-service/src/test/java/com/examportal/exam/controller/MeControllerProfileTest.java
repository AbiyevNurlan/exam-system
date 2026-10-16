package com.examportal.exam.controller;

import com.examportal.common.domain.dto.profile.ParticipantProfileDtos;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.client.UserDirectoryClient;
import com.examportal.exam.repository.MyStatsRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B11: GET/PUT /api/me/profile act on the user of the token only: the user and the actor of a save
 * are both that user, there is no other id to give.
 */
class MeControllerProfileTest {

    private final UserDirectoryClient directory = mock(UserDirectoryClient.class);
    private final MeController controller = new MeController(mock(MyStatsRepository.class), directory);

    private static UserPrincipal principal(UUID id) {
        UserPrincipal principal = mock(UserPrincipal.class);
        when(principal.getId()).thenReturn(id);
        return principal;
    }

    @Test
    void readsAndSavesOnlyTheOwnProfile() {
        UUID me = UUID.randomUUID();
        ParticipantProfileDtos.UpdateRequest request = ParticipantProfileDtos.UpdateRequest.builder().category("INTERN").build();
        when(directory.profile(me)).thenReturn(Mono.just(ParticipantProfileDtos.MyProfile.builder().build()));
        when(directory.saveProfile(eq(me), eq(me), any())).thenReturn(Mono.just(ParticipantProfileDtos.Profile.builder().category("INTERN").build()));

        assertThat(controller.myProfile(principal(me)).block().isSuccess()).isTrue();
        assertThat(controller.saveMyProfile(principal(me), request).block().getData().getCategory()).isEqualTo("INTERN");
        verify(directory).profile(me);
        verify(directory).saveProfile(me, me, request);
    }
}
