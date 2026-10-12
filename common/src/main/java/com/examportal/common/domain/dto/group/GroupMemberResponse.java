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
    private GroupMemberSource source;
    private UUID addedBy;
    private LocalDateTime addedAt;
}