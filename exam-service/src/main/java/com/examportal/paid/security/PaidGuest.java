package com.examportal.paid.security;

import java.util.UUID;

/**
 * A signed-in guest of B13a in exam-service (role GUEST): the guest's id and 7-digit code from the token
 */
public record PaidGuest(UUID id, int participantCode) {
}
