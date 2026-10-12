package com.examportal.common.domain.dto.group;

import com.examportal.common.domain.entity.GroupMemberSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Group Member Response DTO
 * Members are identified by user id only; names and emails live in auth-service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupMemberResponse {

    private UUID userId;

    // From auth-service; null when the account no longer exists or is inactive
    private String firstName;
    private String lastName;
    // Masked (first character and domain), never the full address
    private String email;

    private GroupMemberSource source;
    private UUID addedBy;
    private LocalDateTime addedAt;
}