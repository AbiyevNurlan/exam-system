package com.examportal.common.domain.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Authentication Response DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    private String accessToken;
    private String refreshToken;
    @Builder.Default
    private String tokenType = "Bearer";
    private Long expiresIn;
    private UserInfo user;
    /** B13: the password was set by an administrator; until it is changed the tokens reach only
     *  POST /auth/change-password and POST /auth/logout (403 PASSWORD_CHANGE_REQUIRED elsewhere) */
    private boolean mustChangePassword;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {
        private UUID id;
        private String email;
        private String firstName;
        private String lastName;
        private String role;
        private boolean emailVerified;
        /** B13: the same as AuthResponse.mustChangePassword */
        private boolean mustChangePassword;
    }
}
