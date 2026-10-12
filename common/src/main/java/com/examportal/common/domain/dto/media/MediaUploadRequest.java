package com.examportal.common.domain.dto.media;

import com.examportal.common.domain.entity.MediaKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Media Upload Request DTO
 * Asks for a presigned URL to upload one file directly to the storage.
 * contentType and sizeBytes are what the client intends to send; both are checked again against
 * the stored file when the upload is confirmed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaUploadRequest {

    @NotNull(message = "Kind is required")
    private MediaKind kind;

    @NotBlank(message = "Content type is required")
    @Size(max = 100, message = "Content type cannot exceed 100 characters")
    private String contentType;

    @NotNull(message = "Size is required")
    @Positive(message = "Size must be positive")
    private Long sizeBytes;

    // Text alternative shown to participants. On an option image it must not give the answer away.
    @Size(max = 300, message = "Alt text cannot exceed 300 characters")
    private String altText;
}