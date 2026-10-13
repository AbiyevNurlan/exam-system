package com.examportal.common.domain.dto.monitoring;

import com.examportal.common.domain.entity.FraudEventType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Violation Response DTO
 * One fraud event of a session. id only grows: ask again with sinceId = the last id seen to get
 * the newer ones.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ViolationResponse {

    private Long id;
    private FraudEventType type;
    private LocalDateTime occurredAt;
    private String details;
}
