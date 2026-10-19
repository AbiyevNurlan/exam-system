package com.examportal.exam.service;

import com.examportal.common.domain.entity.GroupAuditLog;
import com.examportal.exam.repository.GroupAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Group Audit Service
 * Writes group_audit_log. Call it inside the transaction of the change it records, so a change
 * is never stored without its log row. Details must not contain full email addresses.
 */
@Service
@RequiredArgsConstructor
public class GroupAuditService {

    public static final String GROUP_CREATED = "GROUP_CREATED";
    public static final String GROUP_UPDATED = "GROUP_UPDATED";
    public static final String GROUP_DELETED = "GROUP_DELETED";
    public static final String MEMBER_ADDED = "MEMBER_ADDED";
    public static final String MEMBER_REMOVED = "MEMBER_REMOVED";
    public static final String INVITE_CREATED = "INVITE_CREATED";
    public static final String INVITE_CANCELLED = "INVITE_CANCELLED";
    public static final String EXAM_AUDIENCE_CHANGED = "EXAM_AUDIENCE_CHANGED";
    public static final String CSV_IMPORT = "CSV_IMPORT";
    // An ADMIN changed an exam that someone else created (targetUserId = the exam's creator)
    public static final String EXAM_UPDATED_BY_ADMIN = "EXAM_UPDATED_BY_ADMIN";
    public static final String EXAM_PUBLISHED_BY_ADMIN = "EXAM_PUBLISHED_BY_ADMIN";
    public static final String EXAM_ARCHIVED_BY_ADMIN = "EXAM_ARCHIVED_BY_ADMIN";
    public static final String EXAM_STATUS_CHANGED_BY_ADMIN = "EXAM_STATUS_CHANGED_BY_ADMIN";
    public static final String EXAM_DELETED_BY_ADMIN = "EXAM_DELETED_BY_ADMIN";
    // The owner deleted the own exam (by someone else: EXAM_DELETED_BY_ADMIN)
    public static final String EXAM_DELETED = "EXAM_DELETED";
    // Written for every reschedule, also the owner's own (targetUserId = the exam's creator)
    public static final String EXAM_RESCHEDULED = "EXAM_RESCHEDULED";
    // Title or description changed (PATCH /api/exams/{id}/meta); written for the owner too
    public static final String EXAM_META_UPDATED = "EXAM_META_UPDATED";
    // A person gave points to an open-ended answer, for the first time or again (targetUserId = the
    // participant; details: exam, session, answer, old and new points)
    public static final String ANSWER_GRADED = "ANSWER_GRADED";
    public static final String ANSWER_REGRADED = "ANSWER_REGRADED";
    // Someone downloaded the results of an exam as CSV (targetUserId = the exam's creator; details:
    // exam, number of rows, order, whether a search was used)
    public static final String RESULTS_EXPORTED = "RESULTS_EXPORTED";
    // One row per bulk addition (details: what was asked for, matched / added / skipped)
    public static final String MEMBERS_BULK_ADDED = "MEMBERS_BULK_ADDED";
    // Join link of a group and what happened through it (targetUserId = the user who joined or asked)
    public static final String LINK_CREATED = "LINK_CREATED";
    public static final String LINK_REGENERATED = "LINK_REGENERATED";
    public static final String LINK_DISABLED = "LINK_DISABLED";
    public static final String LINK_ENABLED = "LINK_ENABLED";
    public static final String LINK_UPDATED = "LINK_UPDATED";
    public static final String JOIN_VIA_LINK = "JOIN_VIA_LINK";
    public static final String JOIN_REQUESTED = "JOIN_REQUESTED";
    // B13: an administrator set the limits of a teacher (target = the teacher)
    public static final String INSTRUCTOR_LIMITS_CHANGED = "INSTRUCTOR_LIMITS_CHANGED";
    // JOIN_APPROVED and JOIN_REJECTED are written in SQL by GroupJoinRepository.decide
    // INVITE_APPLIED is written in SQL by GroupImportRepository.applyPendingInvites

    private final GroupAuditLogRepository auditRepository;

    public Mono<Void> record(UUID actorId, String action, Long groupId, UUID targetUserId, String details) {
        return auditRepository.save(GroupAuditLog.builder()
                        .actorId(actorId)
                        .action(action)
                        .groupId(groupId)
                        .targetUserId(targetUserId)
                        .details(details)
                        .createdAt(LocalDateTime.now())
                        .build())
                .then();
    }
}