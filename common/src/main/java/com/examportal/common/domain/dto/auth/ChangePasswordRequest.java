package com.examportal.common.domain.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * POST /auth/change-password. The new password follows the registration policy (RegisterRequest,
 * ResetPasswordRequest). Neither password is ever part of toString(), so none can reach a log line.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangePasswordRequest {

    @ToString.Exclude
    @NotBlank(message = "Current password is required")
    @Size(max = 100, message = "Password must not exceed 100 characters")
    private String currentPassword;

    @ToString.Exclude
    @NotBlank(message = "New password is required")
    @Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters")
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).*$",
             message = "Password must contain at least one uppercase letter, one lowercase letter, and one digit")
    private String newPassword;
}
