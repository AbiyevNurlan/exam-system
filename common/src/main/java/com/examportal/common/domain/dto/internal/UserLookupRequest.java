package com.examportal.common.domain.dto.internal;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * User Lookup Request DTO (service to service, never exposed through the gateway)
 * Asks auth-service which of these emails and ids belong to active users.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLookupRequest {

    public static final int MAX_ITEMS = 500;

    @Size(max = MAX_ITEMS, message = "Too many emails")
    private List<String> emails;

    @Size(max = MAX_ITEMS, message = "Too many ids")
    private List<UUID> ids;
}