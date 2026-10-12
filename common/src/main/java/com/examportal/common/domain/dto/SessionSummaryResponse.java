package com.examportal.common.domain.dto;

import com.examportal.common.domain.entity.SessionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Session Summary Response DTO
 * One row of an exam's results list for the instructor/admin.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionSummaryResponse {

    private String sessionId;
    private UUID userId;
    private SessionStatus status;
    private BigDecimal score;
    private BigDecimal percentage;
    private Boolean passed;
    private LocalDateTime submittedAt;
}
