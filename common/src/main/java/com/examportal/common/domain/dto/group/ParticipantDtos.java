package com.examportal.common.domain.dto.group;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
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
 * Participant DTOs
 * The admin's participant list (users with the role STUDENT) and adding many of them to a group.
 */
public final class ParticipantDtos {

    public static final int BULK_CAP = 20_000;
    public static final int MAX_BULK_IDS = 1000;

    private ParticipantDtos() {
    }

    /**
     * Which participants are meant; every field is optional, the given ones are combined with AND.
     * The same filter drives the list and the bulk addition, so "add everybody I see" means the same people.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Filter {
        // First name, last name, "first last" or email prefix (ignoring case), or a full user id; at least 2 characters
        private String q;
        // Members of this group only
        private Long groupId;
        // Only participants who are in no group at all; cannot be combined with groupId
        private Boolean noGroup;
        private Boolean verified;
        // Locked right now
        private Boolean blocked;
        // yyyy-MM-dd, calendar days in the exam day zone (Asia/Baku by default), both inclusive
        private String registeredFrom;
        private String registeredTo;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Participant {
        private UUID id;
        private String firstName;
        private String lastName;
        // Not masked: the list is for administrators only
        private String email;
        // 7-digit participant code (display and search only, never security)
        private Integer participantCode;
        private LocalDateTime registeredAt;
        private boolean emailVerified;
        private boolean blocked;
        // Set while a temporary lock is in force
        private LocalDateTime lockedUntil;
        // The first three groups by name; groupCount says how many there are in all
        private List<GroupRef> groups;
        private int groupCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupRef {
        private Long id;
        private String name;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private long total;
        // Since midnight of the exam day zone
        private long registeredToday;
        private long unverified;
        // In no group at all
        private long noGroup;
        // Locked right now with an end time
        private long temporarilyBlocked;
        // Locked right now, with or without an end time
        private long blocked;
        private LocalDateTime generatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkByFilterRequest {
        @Valid
        private Filter filter;
        // true: report only, write nothing
        private boolean dryRun;
        // For a real run: willAdd as the dry run reported it; a different number now is 409 FILTER_COUNT_CHANGED
        private Integer expectedCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkByIdsRequest {
        @NotNull(message = "userIds is required")
        @Size(min = 1, max = MAX_BULK_IDS, message = "Between 1 and 1000 user ids")
        private List<@NotNull(message = "A user id must not be null") UUID> userIds;
        private boolean dryRun;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BulkResult {
        private boolean dryRun;
        // Participants the filter (or the id list) matches
        private long matched;
        // Of those, members of the group already
        private long alreadyMembers;
        // matched - alreadyMembers
        private long willAdd;
        // A request that would add more than this is refused
        private int cap;
        // Real runs only: memberships created by this call
        private Long added;
        // Real runs only: matched users that were members already (also when someone else added them meanwhile)
        private Long skipped;
    }
}
