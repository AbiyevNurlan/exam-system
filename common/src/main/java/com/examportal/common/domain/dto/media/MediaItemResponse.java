package com.examportal.common.domain.dto.media;

import com.examportal.common.domain.entity.MediaKind;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Media Item Response DTO
 * A media attached to a question or an option, inside exam and question responses.
 * previewUrl is set only for ADMIN and the INSTRUCTOR who owns the exam. Participants never get a
 * URL here: they ask for one per media through their session.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MediaItemResponse {

    private UUID id;
    private MediaKind kind;
    // Visible to participants; on an option image it must not reveal the answer
    private String altText;
    private Integer durationSeconds;
    private Integer position;
    private Integer maxPlays;
    private String previewUrl;
}