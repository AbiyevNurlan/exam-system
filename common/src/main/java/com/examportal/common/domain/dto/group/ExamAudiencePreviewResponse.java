package com.examportal.common.domain.dto.group;

import com.examportal.common.domain.entity.ExamAccessType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Exam Audience Preview Response DTO
 * How many participants an audience would reach, before it is stored: the members of each group
 * and how many different people that is (somebody in two of the groups counts once).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamAudiencePreviewResponse {

    private Long examId;
    // The access type the exam has now; PUBLIC means everybody may take it, whatever the groups say
    private ExamAccessType accessType;
    // True when the groups come from the exam's stored audience (no groupIds were given)
    private boolean storedAudience;
    private List<GroupMembers> perGroup;
    // Different users in at least one of the groups
    private long distinctEligible;
    // Requested groups that do not exist (or were deleted); they count towards nothing
    private List<Long> unknownGroupIds;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupMembers {
        private Long groupId;
        private String name;
        private long members;
    }
}
