package com.examportal.guest;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row of guest_participants (V9): a participant of paid open exams without a password and without an account
 */
public record Guest(
        UUID id,
        String email,
        LocalDateTime emailVerifiedAt,
        String firstName,
        String lastName,
        String phone,
        int participantCode,
        LocalDateTime consentAt,
        String consentVersion,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt,
        boolean blocked,
        String blockedReason) {
}
