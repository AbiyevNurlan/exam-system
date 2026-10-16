package com.examportal.common.domain.dto.participant;

import com.examportal.common.domain.dto.AuditEntryResponse;
import com.examportal.common.domain.dto.MyStatsResponse;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.internal.AdminAuditQuery;
import com.examportal.common.domain.dto.profile.ParticipantProfileDtos;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Participant Card DTOs (B11)
 * GET /api/admin/participants/{id}: everything about one participant on one screen. The account and the
 * profile come from auth-service in one internal call (AuthCard); groups, attempts, statistics and exam
 * events from exam-service. Built field by field: a password hash, a token, an OTP or a refresh token
 * cannot end up in it because no such field exists here.
 */
public final class ParticipantCardDtos {

    public static final int DEFAULT_SESSIONS = 20;
    public static final int MAX_SESSIONS = 50;
    public static final int EVENTS = 20;

    // What the caller sees: everything (ADMIN), or only the own groups and exams (INSTRUCTOR)
    public static final String SCOPE_ALL = "ALL";
    public static final String SCOPE_OWN = "OWN";

    private ParticipantCardDtos() {
    }

    /**
     * GET /internal/users/{id}/card (service to service): the account, its profile and its newest
     * account events (admin_audit_log rows where the user is actor or target)
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuthCard {
        private UUID id;
        private Integer participantCode;
        private String firstName;
        private String lastName;
        private String email;
        private String phoneNumber;
        private String role;
        private LocalDateTime registeredAt;
        private boolean emailVerified;
        private boolean active;
        private boolean blocked;
        private LocalDateTime lockedUntil;
        private ParticipantProfileDtos.Profile profile;
        private List<AdminAuditQuery.Entry> accountEvents;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Card {
        // For the links of the screen only; the frontend shows participantCode
        private UUID id;
        private Integer participantCode;
        private String firstName;
        private String lastName;
        private String email;
        private String phoneNumber;
        private String role;
        private LocalDateTime registeredAt;
        private boolean emailVerified;
        // false: the account was deactivated (it cannot sign in)
        private boolean active;
        // Locked right now; lockedUntil set while a temporary lock is in force
        private boolean blocked;
        private LocalDateTime lockedUntil;
        // null: no profile was saved yet
        private ParticipantProfileDtos.Profile profile;
        // ALL for an administrator; OWN for an instructor (only the own groups and exams below, no events)
        private String scope;
        private List<Membership> groups;
        // The first page of attempts, newest first; more with GET /api/admin/participants/{id}/sessions
        private PageResponse<SessionRow> sessions;
        // The rule of GET /api/me/stats (visible results only), over the exams of the scope
        private MyStatsResponse stats;
        // The newest events of both audit logs where the user acted or was the target (administrators only)
        private List<AuditEntryResponse> recentEvents;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Membership {
        private Long groupId;
        private String groupName;
        // ADMIN, CSV, BULK or LINK
        private String source;
        private LocalDateTime addedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionRow {
        private String sessionId;
        private Long examId;
        private String examTitle;
        // IN_PROGRESS, COMPLETED, ...
        private String status;
        // NOT_REQUIRED, PENDING, COMPLETE
        private String gradingStatus;
        private BigDecimal score;
        private BigDecimal totalPoints;
        private BigDecimal percentage;
        private Boolean passed;
        private LocalDateTime startedAt;
        private LocalDateTime submittedAt;
    }
}
