package com.examportal.common.domain.dto.internal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Participant Query DTOs (service to service, never exposed through the gateway)
 * exam-service asks auth-service for participants (users with the role STUDENT): a page of them,
 * the ids of all that match, or summary counts. Group membership lives in exam-service, so a group
 * filter arrives here as a set of ids to keep or to leave out.
 */
public final class ParticipantQuery {

    public static final int MIN_SEARCH_LENGTH = 2;
    public static final int MAX_SEARCH_LENGTH = 100;
    public static final int MAX_PAGE_SIZE = 50;
    // More ids than this are never returned in one answer
    public static final int MAX_IDS = 100_000;

    private ParticipantQuery() {
    }

    /**
     * Every field is optional; the given ones are combined with AND. Dates are UTC.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Filter {
        // Prefix of the first name, last name, "first last" or email (ignoring case), or a full user id
        private String q;
        private Boolean verified;
        // Locked right now (a temporary lock that has run out does not count)
        private Boolean blocked;
        // created_at >= registeredFrom and created_at < registeredTo
        private LocalDateTime registeredFrom;
        private LocalDateTime registeredTo;
        // Only these users / none of these users; null = no such restriction, empty = nobody / everybody
        private List<UUID> includeIds;
        private List<UUID> excludeIds;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageRequest {
        private Filter filter;
        private int page;
        private int size;
        // createdAt (default), name or email
        private String sort;
        private boolean ascending;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageResponse {
        private long total;
        private List<Participant> users;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Participant {
        private UUID id;
        private String email;
        private String firstName;
        private String lastName;
        private boolean emailVerified;
        private boolean blocked;
        private LocalDateTime lockedUntil;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IdsRequest {
        private Filter filter;
        // At most MAX_IDS
        private int limit;
    }

    /**
     * total is how many match; ids holds at most the asked limit of them (newest first)
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IdsResponse {
        private long total;
        private List<UUID> ids;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SummaryRequest {
        // The UTC moment today starts in the day zone
        private LocalDateTime todayFrom;
        // Users who are a member of at least one group
        private List<UUID> memberIds;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SummaryResponse {
        private long total;
        private long registeredToday;
        private long unverified;
        private long noGroup;
        // Locked right now with an end time
        private long temporarilyBlocked;
        // Locked right now, with or without an end time
        private long blocked;
    }
}
