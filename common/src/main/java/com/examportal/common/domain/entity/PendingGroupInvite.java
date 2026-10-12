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
 * Pending Group Invite Entity
 * An email that was imported into a group before a verified account existed for it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("pending_group_invites")
public class PendingGroupInvite {

    @Id
    private Long id;

    @Column("group_id")
    private Long groupId;

    @Column("email_normalized")
    private String emailNormalized;

    @Column("created_by")
    private UUID createdBy;

    @Column("created_at")
    private LocalDateTime createdAt;
}