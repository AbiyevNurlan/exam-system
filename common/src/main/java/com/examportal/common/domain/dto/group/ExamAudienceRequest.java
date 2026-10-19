package com.examportal.common.domain.dto.group;

import com.examportal.common.domain.entity.ExamAccessType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Exam Audience Request DTO
 * GROUP needs at least one group; for PUBLIC the group list is ignored and the audience is cleared.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamAudienceRequest {

    @NotNull(message = "Access type is required")
    private ExamAccessType accessType;

    @Size(max = 50, message = "An exam can have at most 50 audience groups")
    private List<@NotNull(message = "Group id must not be null") Long> groupIds;

    // B12a: why (optional, 3-500 characters), written to the exam's history
    private String changeReason;
}