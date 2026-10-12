package com.examportal.common.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;
/**
 * Group Audit Log Entity
 * One change to a group, its members or an exam's audience.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("group_audit_log")
public class GroupAuditLog {

    @Id
    private Long id;

    @Column("actor_id")
    private UUID actorId;

    @Column("action")
    private String action;

    @Column("group_id")
    private Long groupId;

    @Column("target_user_id")
    private UUID targetUserId;

    @Column("details")
    private String details;

    @Column("created_at")
    private LocalDateTime createdAt;
}