package com.examportal.common.domain.dto.group;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Audience Preview Request DTO
 * POST /api/exams/audience/preview: the groups of an audience that is not stored yet
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudiencePreviewRequest {

    @NotNull(message = "groupIds is required")
    @Size(max = 50, message = "At most 50 groups can be previewed")
    private List<@NotNull(message = "Group id must not be null") Long> groupIds;
}
