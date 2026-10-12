package com.examportal.common.domain.dto.group;

import com.examportal.common.domain.entity.ExamAccessType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Exam Audience Response DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamAudienceResponse {

    private Long examId;
    private ExamAccessType accessType;
    private List<Long> groupIds;
}