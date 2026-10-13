package com.examportal.common.domain.dto.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * User Search Request DTO (service to service, never exposed through the gateway)
 * Asks auth-service for the active users whose first name, last name, full name or email starts
 * with q (ignoring case), or whose id is q.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSearchRequest {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 100;
    public static final int MAX_LIMIT = 200;

    @NotBlank(message = "q is required")
    @Size(min = MIN_LENGTH, max = MAX_LENGTH, message = "q must be between 2 and 100 characters")
    private String q;

    @Min(value = 1, message = "limit must be at least 1")
    @Max(value = MAX_LIMIT, message = "limit cannot exceed 200")
    private Integer limit;
}
