package com.examportal.common.domain.dto.media;

import com.examportal.common.domain.entity.MediaKind;
import com.examportal.common.domain.entity.MediaStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Media Response DTO
 * Metadata of an uploaded file for its owner. Never contains the storage key or a URL.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaResponse {

    private UUID id;
    private MediaKind kind;
    private String contentType;
    private Long sizeBytes;
    private MediaStatus status;
    private String altText;
    private LocalDateTime createdAt;
    private LocalDateTime activatedAt;
}