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
 * User Group Member Entity
 * Membership of one user (exam_auth.users.id) in one group.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("user_group_members")
public class UserGroupMember {

    @Id
    private Long id;

    @Column("group_id")
    private Long groupId;

    @Column("user_id")
    private UUID userId;

    @Column("source")
    private GroupMemberSource source;

    @Column("added_by")
    private UUID addedBy;

    @Column("created_at")
    private LocalDateTime createdAt;
}