package com.examportal.common.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Audit Entry Response DTO
 * One row of the audit list (GET /api/admin/audit): from group_audit_log of exam-service (groups,
 * memberships, join links, exams, grading, exports) or from admin_audit_log of auth-service
 * (accounts). Join codes, OTP codes and passwords are never written to either, and details are
 * cleaned once more before they are sent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditEntryResponse {

    // "G-<number>" for exam-service rows, "A-<uuid>" for auth-service rows
    private String id;
    // EXAM_SERVICE or AUTH_SERVICE
    private String source;
    private String action;
    private UUID actorId;
    // "First Last" of the actor, when the user directory answered
    private String actorName;
    // Participant code of the actor (display only)
    private Integer actorCode;
    // GROUP, EXAM or USER
    private String targetType;
    private String targetId;
    private UUID targetUserId;
    private String targetUserName;
    // Participant code of the target user (display only)
    private Integer targetUserCode;
    private String details;
    private LocalDateTime createdAt;
}
