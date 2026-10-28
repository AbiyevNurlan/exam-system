package com.examportal.common.domain.dto;

import com.examportal.common.domain.dto.history.ExamHistoryDtos;
import com.examportal.common.domain.entity.ExamAccessType;
import com.examportal.common.domain.entity.ExamStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Managed Exam Response DTO
 * One row of the management list (GET /api/exams/manage): the exam without its questions, with
 * how often and by how many different users it was started.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManagedExamResponse {

    private Long id;
    private String title;
    private ExamStatus status;
    private ExamAccessType accessType;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationMinutes;
    private Integer maxAttempts;
    // null = no daily limit
    private Integer maxAttemptsPerDay;
    private Integer totalQuestions;
    private BigDecimal totalPoints;
    // Every session, whatever its status
    private long sessionCount;
    // Different users among those sessions
    private long participantCount;
    private UUID createdBy;
    private LocalDateTime createdAt;

    // The active groups of the audience (at most 50, sorted); empty for a PUBLIC exam
    private int audienceGroupCount;
    private List<Long> groupIds;

    // B12a: the newest row of the exam's history (null for an exam not changed since B12a; lastModifiedAt then
    // falls back to the exam's updatedAt)
    private ExamHistoryDtos.Actor lastModifiedBy;
    private LocalDateTime lastModifiedAt;

    // B13b: AUDIENCE (today's rule) or PAID_OPEN. The sale of a PAID_OPEN exam; all of them null for AUDIENCE.
    // priceMinor in whole minor units (qəpik).
    private String accessMode;
    private Integer priceMinor;
    private String currency;
    private Integer seatLimit;
    private Integer seatsTaken;
    private LocalDateTime salesOpenAt;
    private LocalDateTime salesCloseAt;
}
