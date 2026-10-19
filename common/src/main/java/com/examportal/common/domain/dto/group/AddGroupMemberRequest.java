package com.examportal.common.domain.dto.group;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Add Group Member Request DTO
 * Exactly one of userId, email and participantCode must be given.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddGroupMemberRequest {

    private UUID userId;

    @Email(message = "Email must be valid")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    private String email;

    /** B13: the 7-digit participant code the participant gave the teacher (exact match only) */
    @Min(value = 1_000_000, message = "A participant code has 7 digits")
    @Max(value = 9_999_999, message = "A participant code has 7 digits")
    private Integer participantCode;
}