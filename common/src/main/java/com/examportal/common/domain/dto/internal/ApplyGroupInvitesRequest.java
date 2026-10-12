package com.examportal.common.domain.dto.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Apply Group Invites Request DTO (service to service, never exposed through the gateway)
 * Sent by auth-service for a user whose email is verified.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplyGroupInvitesRequest {

    @NotNull(message = "User id is required")
    private UUID userId;

    @NotBlank(message = "Email is required")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    private String email;
}