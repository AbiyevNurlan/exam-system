package com.examportal.common.domain.dto;

import com.examportal.common.domain.entity.FraudEventType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Fraud Event Request DTO
 * Something suspicious the participant's browser noticed during a session. It is only recorded:
 * nothing is decided from it automatically.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FraudEventRequest {

    @NotNull(message = "Type is required")
    private FraudEventType type;

    // When the browser saw it; the server time is used when omitted
    private LocalDateTime occurredAt;

    @Size(max = 500, message = "Details cannot exceed 500 characters")
    private String details;
}