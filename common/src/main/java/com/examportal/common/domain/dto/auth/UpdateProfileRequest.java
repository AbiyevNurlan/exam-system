package com.examportal.common.domain.dto.auth;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PUT /auth/profile: the fields a user may change on the own account. The email, the role and the
 * password are not here (the password has POST /auth/change-password). The frontend sends the
 * phone as "phone"; "phoneNumber" (the name in RegisterRequest and the user) is accepted as well.
 * An empty phone removes it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProfileRequest {

    @NotBlank(message = "First name is required")
    @Size(min = 2, max = 50, message = "First name must be between 2 and 50 characters")
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(min = 2, max = 50, message = "Last name must be between 2 and 50 characters")
    private String lastName;

    // Optional; an Azerbaijani number, stored as E.164 (+994 and 9 digits, PhoneNumbers). Not unique, not verified.
    @JsonAlias("phoneNumber")
    @Size(max = 25, message = "Phone number must not exceed 25 characters")
    @Pattern(regexp = "^[+0-9\\s().\\-]*$", message = "Invalid phone number format")
    private String phone;
}
