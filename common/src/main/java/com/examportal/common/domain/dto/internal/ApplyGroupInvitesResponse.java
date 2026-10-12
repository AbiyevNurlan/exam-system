package com.examportal.common.domain.dto.internal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Apply Group Invites Response DTO (service to service)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplyGroupInvitesResponse {

    /** Number of groups the user was added to */
    private long applied;
}