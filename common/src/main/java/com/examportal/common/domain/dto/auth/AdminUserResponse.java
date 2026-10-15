package com.examportal.common.domain.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Admin User Response DTO
 * A user as shown to administrators. Never carries the password hash.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserResponse {

    private UUID id;
    private String email;
    // 7-digit participant code (display and search only, never security)
    private Integer participantCode;
    private String firstName;
    private String lastName;
    private String role;
    private boolean locked;
    private LocalDateTime lockedUntil;
    private int failedAttempts;
    private boolean emailVerified;
    private LocalDateTime createdAt;
}
