package com.examportal.exam.repository;

import com.examportal.common.domain.entity.GroupAuditLog;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;

/**
 * Group Audit Log Repository
 * Append-only: rows are written through GroupAuditService and never changed.
 */
@Repository
public interface GroupAuditLogRepository extends R2dbcRepository<GroupAuditLog, Long> {
}