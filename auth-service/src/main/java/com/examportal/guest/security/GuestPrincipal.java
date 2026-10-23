package com.examportal.guest.security;

import java.util.UUID;

/**
 * Who a valid guest access token belongs to (role GUEST); never an account
 */
public record GuestPrincipal(UUID id, int participantCode) {
}
