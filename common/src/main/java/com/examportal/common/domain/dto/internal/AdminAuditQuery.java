package com.examportal.common.domain.dto.internal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Admin Audit Query
 * exam-service asks auth-service for the entries of its admin_audit_log (unlocking accounts,
 * verifying emails by hand) to show them in the one audit list: POST /internal/audit/admin.
 * Only ids, the action and the time cross the wire; auth-service stores no details for these.
 */
public final class AdminAuditQuery {

    public static final int MAX_LIMIT = 10_000;

    private AdminAuditQuery() {
    }

    /**
     * Every field is optional; the given ones are combined with AND. from / to are UTC, from
     * inclusive, to exclusive. q matches the action, ignoring case. limit: the newest this many.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Request {
        private String action;
        private UUID actorId;
        private LocalDateTime from;
        private LocalDateTime to;
        private String q;
        private int limit;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Entry {
        private UUID id;
        private UUID actorId;
        private String action;
        private UUID targetUserId;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        // All entries matching the request
        private long total;
        // The newest of them, at most limit, newest first
        private List<Entry> entries;
    }
}
