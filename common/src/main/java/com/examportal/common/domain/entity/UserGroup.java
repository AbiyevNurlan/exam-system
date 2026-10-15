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
 * User Group Entity
 * A named set of users; exams with access type GROUP are limited to the members of their audience groups.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("user_groups")
public class UserGroup {

    @Id
    private Long id;

    @Column("name")
    private String name;

    @Column("description")
    private String description;

    @Column("created_by")
    private UUID createdBy;

    // Who manages the group: an INSTRUCTOR only the own groups, an ADMIN every group (V12)
    @Column("owner_id")
    private UUID ownerId;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Column("active")
    private Boolean active;
}