package com.examportal.common.domain.dto.media;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

/**
 * Media Upload Response DTO
 * Where and how to upload: one HTTP PUT of the file to uploadUrl with exactly the given headers
 * (and the declared size), then POST /api/media/{mediaId}/confirm.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaUploadResponse {

    private UUID mediaId;
    private String uploadUrl;
    private String method;
    private Map<String, String> headers;
    // Seconds until uploadUrl stops working
    private long expiresIn;
}