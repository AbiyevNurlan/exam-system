package com.examportal.common.domain.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * GET /auth/me: the signed-in user, always read from the database for the id in the token.
 * No language preference is stored yet, so there is no such field.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrentUserResponse {

    private UUID id;
    private String email;
    private String firstName;
    private String lastName;
    private String phoneNumber;
    private String role;
    private boolean emailVerified;
    private LocalDateTime createdAt;
}
