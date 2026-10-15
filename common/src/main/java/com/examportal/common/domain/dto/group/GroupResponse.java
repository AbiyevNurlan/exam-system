package com.examportal.common.domain.dto.group;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Group Response DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GroupResponse {

    private Long id;
    private String name;
    private String description;
    private UUID createdBy;
    // Who manages the group (an INSTRUCTOR sees only the own ones)
    private UUID ownerId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // In the list and on the single-group endpoint
    private Long memberCount;
    private Long pendingInviteCount;

    // In the list and on GET /api/admin/groups/{id}
    // Join requests waiting for a decision
    private Long pendingJoinRequests;
    // Exams (not deleted) whose audience is this group
    private Long examCount;
    // State of the join link; exists = false when the group has none (no separate 404 call needed)
    private JoinLinkState joinLink;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JoinLinkState {
        private boolean exists;
        // The fields below are absent when exists = false
        private Boolean enabled;
        // Enabled, not expired and not used up: a participant could join with it right now
        private Boolean usable;
        private Boolean requiresApproval;
        private Integer uses;
        private Integer maxUses;
    }
}
