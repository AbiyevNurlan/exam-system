package com.examportal.guest;

import com.examportal.auth.mail.VerificationMailer;
import com.examportal.common.domain.PhoneNumbers;
import com.examportal.common.exception.ValidationException;
import com.examportal.common.utils.LogMaskUtils;
import com.examportal.guest.security.GuestRefreshTokens;
import com.examportal.guest.security.GuestTokenProvider;
import com.examportal.guest.web.GuestDtos;
import com.examportal.guest.web.GuestDtos.SignInResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * B13a item 3: the guest sign-in without a password.
 *
 * 1. POST /auth/guest/otp/request: a code to the email. One answer whatever happens (a guest or not, an account's
 *    email or not, a code sent or refused by the limits): nothing about the email can be learned from it.
 * 2. POST /auth/guest/otp/verify: with the right code, a known guest is signed in (tokens), a new one gets a profile
 *    token for step 3; an account's email is 409 EMAIL_BELONGS_TO_ACCOUNT, told only to who has the code.
 * 3. POST /auth/guest/profile: name, surname, phone and consent make the guest and sign them in. The profile token
 *    makes one guest: the same token again signs the same guest in and changes nothing.
 * Refresh rotates the refresh token; sign-out ends it. A blocked guest is 403 GUEST_BLOCKED at every step.
 * Codes and tokens are never logged; emails only masked.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestAuthService {

    private final GuestRepository guests;
    private final GuestOtpService otp;
    private final GuestProfileTokens profileTokens;
    private final GuestRefreshTokens refreshTokens;
    private final GuestTokenProvider accessTokens;
    private final VerificationMailer mailer;
    private final TransactionalOperator transactionalOperator;
    private final GuestAuditLog audit;

    private static final String EMAIL_OF_AN_ACCOUNT = "the email belongs to an account: refused";

    public Mono<Void> requestCode(GuestDtos.OtpRequest request) {
        String email = GuestRepository.normalizeEmail(request.email());
        return otp.issue(email)
                .map(code -> {
                    mailer.sendGuestLoginCode(email, code, otp.getTtlMinutes());
                    log.info("Guest sign-in code sent to {}", LogMaskUtils.maskEmail(email));
                    return "sent";
                })
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    log.info("Guest sign-in code for {} refused by the send limits", LogMaskUtils.maskEmail(email));
                    return "refused by the send limits";
                }))
                .flatMap(outcome -> guests.findByEmail(email).map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty())
                        .flatMap(target -> audit.record(GuestAuditLog.OTP_REQUESTED, GuestAuditLog.Actor.anonymous(),
                                target.orElse(null), email, outcome)));
    }

    public Mono<SignInResponse> verifyCode(GuestDtos.OtpVerifyRequest request) {
        String email = GuestRepository.normalizeEmail(request.email());
        return otp.verify(email, request.code())
                .flatMap(valid -> valid ? Mono.<Void>empty() : Mono.error(GuestErrors.otpInvalid()))
                .then(guests.emailBelongsToAccount(email))
                .flatMap(account -> account
                        ? audit.record(GuestAuditLog.OTP_VERIFIED, GuestAuditLog.Actor.anonymous(), null, email, EMAIL_OF_AN_ACCOUNT)
                                .then(Mono.<Boolean>error(GuestErrors.emailBelongsToAccount()))
                        : Mono.just(false))
                .then(guests.findByEmail(email)
                        .flatMap(guest -> transactionalOperator.transactional(
                                audit.record(GuestAuditLog.OTP_VERIFIED, GuestAuditLog.Actor.guest(guest), guest, email, null)
                                        .then(signIn(guest))))
                        .switchIfEmpty(Mono.defer(() -> audit.record(GuestAuditLog.OTP_VERIFIED, GuestAuditLog.Actor.anonymous(), null,
                                        email, "new guest: profile step")
                                .then(profileTokens.create(email))
                                .map(token -> SignInResponse.profileRequired(token, profileTokens.lifetime().toSeconds())))));
    }

    public Mono<SignInResponse> completeProfile(GuestDtos.ProfileRequest request) {
        return Mono.defer(() -> completeProfileNow(request));
    }

    private Mono<SignInResponse> completeProfileNow(GuestDtos.ProfileRequest request) {
        if (!Boolean.TRUE.equals(request.consent())) {
            return Mono.error(GuestErrors.consentRequired());
        }
        String firstName = name("firstName", request.firstName());
        String lastName = name("lastName", request.lastName());
        String phone = phone(request.phone());
        String consentVersion = request.consentVersion().trim();

        Mono<SignInResponse> complete = profileTokens.lock(request.profileToken())
                .filter(GuestProfileTokens.Stored::valid)
                .switchIfEmpty(Mono.error(GuestErrors.profileTokenInvalid()))
                .flatMap(stored -> {
                    if (stored.guestId() != null) {
                        // The same token again: the guest it made, unchanged
                        return guests.findById(stored.guestId()).flatMap(this::signIn);
                    }
                    return guests.lockEmail(stored.email())
                            .then(guests.emailBelongsToAccount(stored.email()))
                            .flatMap(account -> account ? Mono.<Guest>error(GuestErrors.emailBelongsToAccount())
                                    // A guest made for this email by another profile token in the meantime is the guest
                                    : guests.findByEmail(stored.email())
                                            .switchIfEmpty(Mono.defer(() -> guests.insert(stored.email(), stored.verifiedAt(),
                                                    firstName, lastName, phone, consentVersion)
                                                    .doOnNext(guest -> log.info("Guest {} created for {}", guest.participantCode(),
                                                            LogMaskUtils.maskEmail(stored.email())))
                                                    .flatMap(guest -> audit.record(GuestAuditLog.CREATED, GuestAuditLog.Actor.guest(guest),
                                                            guest, null, "consent " + consentVersion).thenReturn(guest)))))
                            .flatMap(guest -> profileTokens.markUsed(request.profileToken(), guest.id()).then(signIn(guest)));
                });
        return transactionalOperator.transactional(complete);
    }

    public Mono<SignInResponse> refresh(GuestDtos.RefreshRequest request) {
        return refreshTokens.rotate(request.refreshToken())
                .flatMap(rotation -> guests.findById(rotation.guestId())
                        .switchIfEmpty(Mono.error(GuestErrors.refreshTokenInvalid()))
                        .flatMap(guest -> guest.blocked()
                                ? refreshTokens.revokeAll(guest.id()).then(Mono.<SignInResponse>error(GuestErrors.blocked()))
                                : Mono.just(SignInResponse.signedIn(accessTokens.issueAccessToken(guest.id(), guest.participantCode()),
                                        rotation.refreshToken(), accessTokens.accessTokenLifetime().toSeconds(), guest))));
    }

    public Mono<Void> logout(GuestDtos.RefreshRequest request) {
        return refreshTokens.revoke(request.refreshToken());
    }

    /** Tokens for a guest who proved the email; part of the caller's transaction */
    private Mono<SignInResponse> signIn(Guest guest) {
        if (guest.blocked()) {
            return Mono.error(GuestErrors.blocked());
        }
        return guests.touchLogin(guest.id())
                .flatMap(signedIn -> audit.record(GuestAuditLog.LOGIN, GuestAuditLog.Actor.guest(signedIn), signedIn, null, null)
                        .thenReturn(signedIn))
                .flatMap(signedIn -> refreshTokens.issue(signedIn.id())
                        .map(refresh -> SignInResponse.signedIn(accessTokens.issueAccessToken(signedIn.id(), signedIn.participantCode()),
                                refresh, accessTokens.accessTokenLifetime().toSeconds(), signedIn)));
    }

    static String name(String field, String value) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.length() < 2 || trimmed.length() > 50) {
            throw new ValidationException(field, "must be between 2 and 50 characters");
        }
        return trimmed;
    }

    static String phone(String value) {
        try {
            String e164 = PhoneNumbers.toE164(value);
            if (e164 == null) {
                throw new ValidationException("phone", "is required");
            }
            return e164;
        } catch (IllegalArgumentException e) {
            throw new ValidationException("phone", e.getMessage());
        }
    }
}
