package com.examportal.common.domain.dto.media;

import com.examportal.common.domain.entity.MediaKind;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Media URL Response DTO
 * A short-lived URL to load one media file directly from the storage (HTTP GET).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MediaUrlResponse {

    private String url;
    private MediaKind kind;
    // Seconds until url stops working
    private long expiresIn;
    // Only for play-limited audio/video inside a session: plays still left after this one
    private Integer remainingPlays;
}