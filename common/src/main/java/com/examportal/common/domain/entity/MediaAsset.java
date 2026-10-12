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
 * Media Asset Entity
 * Metadata of one uploaded file (table media_assets). The file itself is in the object storage
 * under storageKey, which never leaves the service.
 * Rows are written with SQL statements in MediaAssetRepository, not with save().
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("media_assets")
public class MediaAsset {

    @Id
    private UUID id;

    @Column("owner_id")
    private UUID ownerId;

    @Column("kind")
    private MediaKind kind;

    @Column("storage_key")
    private String storageKey;

    @Column("content_type")
    private String contentType;

    @Column("size_bytes")
    private Long sizeBytes;

    @Column("status")
    private MediaStatus status;

    @Column("alt_text")
    private String altText;

    @Column("duration_seconds")
    private Integer durationSeconds;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("activated_at")
    private LocalDateTime activatedAt;
}
