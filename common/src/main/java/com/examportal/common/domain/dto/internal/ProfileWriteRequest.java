package com.examportal.common.domain.dto.internal;

import com.examportal.common.domain.dto.profile.ParticipantProfileDtos;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * PUT /internal/users/{id}/profile (service to service): the profile to store and who stores it.
 * actorId = the user of the path for the participant's own change, an administrator's id otherwise;
 * exam-service has checked who may write.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileWriteRequest {
    private UUID actorId;
    private ParticipantProfileDtos.UpdateRequest profile;
}
