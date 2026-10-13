package com.examportal.common.domain.dto.monitoring;

import com.examportal.common.domain.entity.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Active Exam Response DTO
 * One exam that somebody is taking right now, with the fraud figures of its running sessions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveExamResponse {

    private Long examId;
    private String title;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    // Sessions that are IN_PROGRESS and whose own end time has not passed
    private int inProgressCount;
    // Fraud events of those sessions together
    private long violationCount;
    private RiskLevel highestRiskLevel;
    // How many of those sessions are at each level; every level is present
    private Map<RiskLevel, Integer> riskCounts;
    // The newest fraud event among those sessions; null when there is none
    private LocalDateTime lastEventAt;
}
