package com.examportal.common.domain.dto.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * User Stats Request DTO (service to service, never exposed through the gateway)
 * Asks auth-service for aggregate user counts and the registrations of the last `days` days.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserStatsRequest {

    public static final int MAX_DAYS = 90;
    public static final int DEFAULT_DAYS = 30;

    // 30 when omitted
    @Min(value = 1, message = "days must be at least 1")
    @Max(value = MAX_DAYS, message = "days cannot exceed 90")
    private Integer days;
}
