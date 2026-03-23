package com.examportal.common.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Base Entity
 * Abstract base class for all domain entities with common audit fields.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseEntity {

    @Id
    private UUID id;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    private boolean active = true;
}
