package com.examportal.guest.web;

import com.examportal.guest.Guest;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The request and answer bodies of the guest endpoints (B13a). Names have 2-50 characters as at registration; the
 * phone is an Azerbaijani number, stored as +994 and 9 digits.
 */
public final class GuestDtos {

    private GuestDtos() {
    }

    public static final String SIGNED_IN = "SIGNED_IN";
    public static final String PROFILE_REQUIRED = "PROFILE_REQUIRED";

    public record OtpRequest(
            @NotBlank @Email @Size(max = 255) String email,
            // P0-1: the token of the captcha, once a provider is connected (GuestCaptcha); ignored while it is off
            @Size(max = 4096) String captchaToken) {
    }

    public record OtpVerifyRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Pattern(regexp = "[0-9]{6}", message = "must be 6 digits") String code) {
    }

    public record ProfileRequest(
            @NotBlank @Size(max = 200) String profileToken,
            @NotBlank @Size(min = 2, max = 50) String firstName,
            @NotBlank @Size(min = 2, max = 50) String lastName,
            @NotBlank @Size(max = 30) String phone,
            @NotNull Boolean consent,
            @NotBlank @Size(max = 32) String consentVersion) {
    }

    public record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {
    }

    /** PATCH /api/guest/me: only the fields that are given change; the email never does */
    public record UpdateMeRequest(
            @Size(min = 2, max = 50) String firstName,
            @Size(min = 2, max = 50) String lastName,
            @Size(max = 30) String phone) {
    }

    public record GuestView(UUID id, int participantCode, String firstName, String lastName, String email, String phone) {

        public static GuestView of(Guest guest) {
            return new GuestView(guest.id(), guest.participantCode(), guest.firstName(), guest.lastName(), guest.email(), guest.phone());
        }
    }

    /**
     * The answer of the sign-in steps: SIGNED_IN with the tokens and the guest, or PROFILE_REQUIRED with the profile
     * token of a new guest (expiresIn and profileTokenExpiresIn in seconds)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SignInResponse(
            String status,
            String accessToken,
            String refreshToken,
            String tokenType,
            Long expiresIn,
            GuestView guest,
            String profileToken,
            Long profileTokenExpiresIn) {

        public static SignInResponse signedIn(String accessToken, String refreshToken, long expiresIn, Guest guest) {
            return new SignInResponse(SIGNED_IN, accessToken, refreshToken, "Bearer", expiresIn, GuestView.of(guest), null, null);
        }

        public static SignInResponse profileRequired(String profileToken, long expiresIn) {
            return new SignInResponse(PROFILE_REQUIRED, null, null, null, null, null, profileToken, expiresIn);
        }
    }

    public record FeatureResponse(boolean paidAccessEnabled) {
    }

    // ---------- the admin's list of guests (item 5) ----------

    public record AdminGuestRow(UUID id, int participantCode, String firstName, String lastName, String email,
                                LocalDateTime createdAt, LocalDateTime lastLoginAt, boolean blocked) {

        public static AdminGuestRow of(Guest guest) {
            return new AdminGuestRow(guest.id(), guest.participantCode(), guest.firstName(), guest.lastName(), guest.email(),
                    guest.createdAt(), guest.lastLoginAt(), guest.blocked());
        }
    }

    public record AdminGuestPage(List<AdminGuestRow> content, long totalElements, int page, int size) {
    }

    /** The admin's card of a guest: code, name, email, phone, created, last sign-in, block and its reason */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminGuestCard(UUID id, int participantCode, String firstName, String lastName, String email, String phone,
                                 LocalDateTime createdAt, LocalDateTime lastLoginAt, boolean blocked, String blockedReason,
                                 String consentVersion, LocalDateTime consentAt) {

        public static AdminGuestCard of(Guest guest) {
            return new AdminGuestCard(guest.id(), guest.participantCode(), guest.firstName(), guest.lastName(), guest.email(),
                    guest.phone(), guest.createdAt(), guest.lastLoginAt(), guest.blocked(), guest.blockedReason(),
                    guest.consentVersion(), guest.consentAt());
        }
    }

    /** B13b item 17: the guest's events, newest first */
    public record EventPage(List<com.examportal.guest.GuestAuditLog.Event> content, long totalElements, int page, int size) {
    }

    /** A block and an unblock both need a reason (3-500 characters), kept on the guest and in the audit */
    public record BlockRequest(@NotNull Boolean blocked, @NotBlank @Size(min = 3, max = 500) String reason) {
    }
}
