package com.examportal.common.domain.dto.group;

import com.examportal.common.domain.entity.ExamAccessType;
import com.examportal.common.domain.entity.ExamStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Group Exam Response DTO
 * An exam (not deleted) whose audience includes a group: GET /api/admin/groups/{id}/exams
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupExamResponse {

    private Long id;
    private String title;
    private ExamStatus status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private ExamAccessType accessType;
}
