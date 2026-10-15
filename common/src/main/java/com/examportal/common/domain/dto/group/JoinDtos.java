package com.examportal.common.domain.dto.group;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Join DTOs
 * The join link of a group: what an administrator sets and sees, and what a participant gets when
 * opening the link and joining.
 */
public final class JoinDtos {

    public static final int MAX_DECISIONS = 500;

    private JoinDtos() {
    }

    /**
     * Settings of a link, for creating it (again) and for changing it. A field that is omitted keeps
     * its value; expiresAt and maxUses can be removed with an explicit null.
     */
    @Data
    @NoArgsConstructor
    public static class LinkRequest {
        private Boolean enabled;
        private Boolean requiresApproval;
        private LocalDateTime expiresAt;
        @Min(value = 1, message = "maxUses must be at least 1")
        @Max(value = 1_000_000, message = "maxUses cannot exceed 1000000")
        private Integer maxUses;

        // True once the field was given (also as null): tells "omitted" from "remove it"
        @JsonIgnore
        private boolean expiresAtPresent;
        @JsonIgnore
        private boolean maxUsesPresent;

        public void setExpiresAt(LocalDateTime expiresAt) {
            this.expiresAt = expiresAt;
            this.expiresAtPresent = true;
        }

        public void setMaxUses(Integer maxUses) {
            this.maxUses = maxUses;
            this.maxUsesPresent = true;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LinkResponse {
        private Long groupId;
        // Participants open /join/{code} in the frontend, which calls GET and POST /api/join/{code}
        private String code;
        private boolean enabled;
        private boolean requiresApproval;
        private LocalDateTime expiresAt;
        private Integer maxUses;
        // Joins and requests made through the current code
        private int uses;
        // Requests of this group still waiting for a decision
        private long pendingRequests;
        // Whether a participant could join with it right now (enabled, not expired, not used up)
        private boolean usable;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    /**
     * What anybody holding a valid code may know: the name of the group and whether joining needs approval
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Info {
        private String groupName;
        private boolean requiresApproval;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JoinResult {
        // MEMBER: in the group now. PENDING: an administrator has to approve first.
        private String status;
        private String groupName;
        // False when this call changed nothing (the caller was a member or had asked already)
        private boolean changed;
    }

    /**
     * A participant's own join request (GET /api/join/requests/mine): the group by name, the state
     * and when it was decided. Who decided is not shown.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OwnRequest {
        private Long id;
        private Long groupId;
        private String groupName;
        // PENDING, APPROVED or REJECTED
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime decidedAt;
        // Only on the newest rejected request of a group while the rejection cooldown runs: from when
        // the link can be used again
        private LocalDateTime canRequestAgainAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Request {
        private Long id;
        private UUID userId;
        private String participantName;
        private String email;
        private String status;
        private LocalDateTime createdAt;
        private UUID decidedBy;
        private LocalDateTime decidedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Decision {
        @NotNull(message = "requestIds is required")
        @Size(min = 1, max = MAX_DECISIONS, message = "Between 1 and 500 request ids")
        private List<@NotNull(message = "A request id must not be null") Long> requestIds;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DecisionResult {
        private int requested;
        // Requests that were open and are decided now
        private int decided;
        // Unknown, of another group, or decided earlier
        private int skipped;
    }
}
