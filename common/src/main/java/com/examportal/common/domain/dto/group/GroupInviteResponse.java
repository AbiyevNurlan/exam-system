package com.examportal.common.domain.dto.group;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Group Invite Response DTO
 * A pending invite: an imported or added email that has no verified account yet.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupInviteResponse {

    private Long id;
    // Masked (first character and domain), never the full address
    private String email;
    private UUID createdBy;
    private LocalDateTime createdAt;
}
