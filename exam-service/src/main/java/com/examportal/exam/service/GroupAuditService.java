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
    // A person gave points to an open-ended answer, for the first time or again (targetUserId = the
    // participant; details: exam, session, answer, old and new points)
    public static final String ANSWER_GRADED = "ANSWER_GRADED";
    public static final String ANSWER_REGRADED = "ANSWER_REGRADED";
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