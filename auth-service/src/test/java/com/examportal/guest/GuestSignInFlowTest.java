package com.examportal.guest;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.examportal.auth.mail.VerificationMailer;
import com.examportal.common.exception.ApplicationException;
import com.examportal.guest.security.GuestPrincipal;
import com.examportal.guest.security.GuestRefreshTokens;
import com.examportal.guest.security.GuestTokenProvider;
import com.examportal.guest.web.GuestDtos;
import com.examportal.guest.web.GuestDtos.SignInResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * B13a item 3 on a real PostgreSQL with every migration: a new guest (code, profile, signed in with a 7-digit code),
 * the same guest coming back with only the email and a code (same id, same participant code), the profile token
 * answering again for the same guest without a second guest, an account's email (409 after the code, no guest),
 * the limits of the codes (cooldown, wrong attempts), consent and phone, a blocked guest, the guest's own data,
 * refresh. No code or token is ever written to the log. Skipped without Docker.
 */
class GuestSignInFlowTest {

    static final String JWT_SECRET = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef-test-secret".getBytes());
    static final String OTP_SECRET = Base64.getEncoder().encodeToString("otp-secret-otp-secret-otp-secret-32b".getBytes());

    private static GuestTestDatabase database;
    private static GuestRepository guests;

    private VerificationMailer mailer;
    private GuestAuthService service;
    private GuestService me;
    private GuestTokenProvider tokens;
    private ListAppender<ILoggingEvent> logs;
    // The captcha hook (item 7): passes unless a test says otherwise
    private com.examportal.guest.captcha.GuestCaptcha captcha = (token, ip) -> reactor.core.publisher.Mono.just(true);

    @BeforeAll
    static void start() {
        database = GuestTestDatabase.start();
        guests = new GuestRepository(database.db);
        database.account("teacher.account@x.test");
    }

    @AfterAll
    static void stop() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void setUp() {
        mailer = mock(VerificationMailer.class);
        tokens = new GuestTokenProvider(JWT_SECRET, 30);
        // Cooldown 0 here, so one test can ask for several codes; the cooldown has its own test below
        service = service(0);
        me = new GuestService(guests, new GuestAuditLog(database.db), database.tx);
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
    }

    private GuestAuthService service(int cooldownSeconds) {
        GuestOtpService otp = new GuestOtpService(database.db, database.tx, guests, OTP_SECRET, cooldownSeconds);
        return new GuestAuthService(guests, otp, new GuestProfileTokens(database.db),
                new GuestRefreshTokens(database.db, database.tx), tokens, mailer, database.tx, new GuestAuditLog(database.db), captcha);
    }

    private String mailedCode(String email) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendGuestLoginCode(eq(email), code.capture(), eq(10));
        clearInvocations(mailer);
        return code.getValue();
    }

    private SignInResponse codeStep(String email) {
        service.requestCode(new GuestDtos.OtpRequest(email, null)).block();
        return service.verifyCode(new GuestDtos.OtpVerifyRequest(email, mailedCode(GuestRepository.normalizeEmail(email)))).block();
    }

    private static GuestDtos.ProfileRequest profile(String token) {
        return new GuestDtos.ProfileRequest(token, " Aysel ", "Quliyeva", "050 123 45 67", true, "2026-10");
    }

    private static String code(Throwable error) {
        return ((ApplicationException) error).getErrorCode();
    }

    private void nothingSecretInTheLog(String... secrets) {
        for (String secret : secrets) {
            assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains(secret));
        }
    }

    @Test
    void aNewGuestThenTheSameGuestAgainWithOnlyTheEmail() {
        String email = "new.guest@x.test";
        SignInResponse first = codeStep(" New.Guest@X.test ");
        assertThat(first.status()).isEqualTo(GuestDtos.PROFILE_REQUIRED);
        assertThat(first.profileTokenExpiresIn()).isEqualTo(900);
        assertThat(first.accessToken()).isNull();

        SignInResponse created = service.completeProfile(profile(first.profileToken())).block();
        assertThat(created.status()).isEqualTo(GuestDtos.SIGNED_IN);
        assertThat(created.expiresIn()).isEqualTo(1800);
        assertThat(created.guest().email()).isEqualTo(email);
        assertThat(created.guest().firstName()).isEqualTo("Aysel");
        assertThat(created.guest().phone()).isEqualTo("+994501234567");
        assertThat(created.guest().participantCode()).isBetween(1_000_000, 9_999_999);
        GuestPrincipal principal = tokens.parse(created.accessToken());
        assertThat(principal.id()).isEqualTo(created.guest().id());

        // Back another day: only the email and a code, the same guest and the same 7-digit ID
        SignInResponse again = codeStep(email);
        assertThat(again.status()).isEqualTo(GuestDtos.SIGNED_IN);
        assertThat(again.guest().id()).isEqualTo(created.guest().id());
        assertThat(again.guest().participantCode()).isEqualTo(created.guest().participantCode());
        assertThat(database.scalar("SELECT count(*) FROM guest_participants WHERE email = '" + email + "'")).isEqualTo(1);

        nothingSecretInTheLog(first.profileToken(), created.accessToken(), created.refreshToken(), again.refreshToken());
    }

    @Test
    void theProfileTokenMakesOneGuestAndAnswersAgainForIt() {
        SignInResponse first = codeStep("twice@x.test");
        SignInResponse made = service.completeProfile(profile(first.profileToken())).block();
        SignInResponse repeated = service.completeProfile(new GuestDtos.ProfileRequest(first.profileToken(), "Other", "Name",
                "+994551112233", true, "2026-10")).block();

        assertThat(repeated.guest().id()).isEqualTo(made.guest().id());
        // Nothing of the second call was written
        assertThat(repeated.guest().firstName()).isEqualTo("Aysel");
        assertThat(database.scalar("SELECT count(*) FROM guest_participants WHERE email = 'twice@x.test'")).isEqualTo(1);

        // After its 15 minutes it is refused
        database.db.sql("UPDATE guest_profile_tokens SET expires_at = LOCALTIMESTAMP - interval '1 second' WHERE email = 'twice@x.test'")
                .then().block();
        assertThatThrownBy(() -> service.completeProfile(profile(first.profileToken())).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("PROFILE_TOKEN_INVALID"));
        assertThatThrownBy(() -> service.completeProfile(profile("made-up")).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("PROFILE_TOKEN_INVALID"));
    }

    @Test
    void anAccountsEmailNeverBecomesAGuest() {
        String email = "teacher.account@x.test";
        // The request answers as for anybody (no exception, the same message in the controller)
        service.requestCode(new GuestDtos.OtpRequest(email, null)).block();
        String sent = mailedCode(email);
        assertThatThrownBy(() -> service.verifyCode(new GuestDtos.OtpVerifyRequest(email, sent)).block())
                .satisfies(error -> {
                    assertThat(code(error)).isEqualTo("EMAIL_BELONGS_TO_ACCOUNT");
                    assertThat(((ApplicationException) error).getStatus().value()).isEqualTo(409);
                });
        assertThat(database.scalar("SELECT count(*) FROM guest_participants WHERE email = '" + email + "'")).isZero();
    }

    @Test
    void wrongCodesBurnTheCodeAndTheCooldownHolds() {
        String email = "limits@x.test";
        service.requestCode(new GuestDtos.OtpRequest(email, null)).block();
        String right = mailedCode(email);
        String wrong = right.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.verifyCode(new GuestDtos.OtpVerifyRequest(email, wrong)).block())
                    .satisfies(error -> assertThat(code(error)).isEqualTo("OTP_INVALID"));
        }
        // The fifth wrong code burned it: the right one no longer works
        assertThatThrownBy(() -> service.verifyCode(new GuestDtos.OtpVerifyRequest(email, right)).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("OTP_INVALID"));

        // With the real cooldown of 60 seconds a second code right away is not sent, and the answer is the same
        GuestAuthService strict = service(60);
        strict.requestCode(new GuestDtos.OtpRequest("cooldown@x.test", null)).block();
        mailedCode("cooldown@x.test");
        strict.requestCode(new GuestDtos.OtpRequest("cooldown@x.test", null)).block();
        verify(mailer, never()).sendGuestLoginCode(eq("cooldown@x.test"), anyString(), anyInt());
        assertThat(database.scalar("SELECT count(*) FROM guest_email_otp WHERE email = 'cooldown@x.test'")).isEqualTo(1);

        // Only the HMAC of a code is kept
        assertThat(database.scalar("SELECT count(*) FROM guest_email_otp WHERE code_hash = '" + right + "'")).isZero();
        nothingSecretInTheLog(right);
    }

    @Test
    void consentAndPhoneAreChecked() {
        SignInResponse first = codeStep("consent@x.test");
        assertThatThrownBy(() -> service.completeProfile(new GuestDtos.ProfileRequest(first.profileToken(), "Aysel", "Quliyeva",
                "+994501234567", false, "2026-10")).block()).satisfies(error -> assertThat(code(error)).isEqualTo("CONSENT_REQUIRED"));
        assertThatThrownBy(() -> service.completeProfile(new GuestDtos.ProfileRequest(first.profileToken(), "Aysel", "Quliyeva",
                "+7 900 123 45 67", true, "2026-10")).block()).satisfies(error -> assertThat(code(error)).isEqualTo("VALIDATION_ERROR"));
        assertThat(database.scalar("SELECT count(*) FROM guest_participants WHERE email = 'consent@x.test'")).isZero();
        // The token was not used up by the refused calls
        assertThat(service.completeProfile(profile(first.profileToken())).block().status()).isEqualTo(GuestDtos.SIGNED_IN);
    }

    @Test
    void aBlockedGuestCannotSignInRefreshOrReadTheirData() {
        SignInResponse made = service.completeProfile(profile(codeStep("blocked@x.test").profileToken())).block();
        GuestPrincipal principal = tokens.parse(made.accessToken());
        assertThat(me.current(principal).block().email()).isEqualTo("blocked@x.test");

        guests.setBlocked(made.guest().id(), true, "test").block();
        assertThatThrownBy(() -> codeStep("blocked@x.test")).satisfies(error -> assertThat(code(error)).isEqualTo("GUEST_BLOCKED"));
        assertThatThrownBy(() -> service.refresh(new GuestDtos.RefreshRequest(made.refreshToken())).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("GUEST_BLOCKED"));
        assertThatThrownBy(() -> me.current(principal).block()).satisfies(error -> assertThat(code(error)).isEqualTo("GUEST_BLOCKED"));
    }

    @Test
    void everyStepIsAuditedWithoutCodesTokensOrFullEmails() {
        String email = "audited.guest@x.test";
        SignInResponse first = codeStep(email);
        SignInResponse made = service.completeProfile(profile(first.profileToken())).block();
        me.update(tokens.parse(made.accessToken()), new GuestDtos.UpdateMeRequest(null, "Məmmədova", null)).block();
        codeStep(email);
        String id = made.guest().id().toString();
        int code = made.guest().participantCode();

        assertThat(database.db.sql("SELECT action FROM guest_audit_log WHERE email_masked = 'a***@x.test' OR target_guest_id = '" + id
                        + "' ORDER BY created_at, action").map(row -> row.get(0, String.class)).all().collectList().block())
                .contains("GUEST_OTP_REQUESTED", "GUEST_OTP_VERIFIED", "GUEST_CREATED", "GUEST_LOGIN", "GUEST_UPDATED");
        assertThat(database.scalar("SELECT count(*) FROM guest_audit_log WHERE action = 'GUEST_CREATED' AND actor_kind = 'GUEST' AND actor_code = "
                + code + " AND target_code = " + code)).isEqualTo(1);
        assertThat(database.scalar("SELECT count(*) FROM guest_audit_log WHERE action = 'GUEST_UPDATED' AND details = 'fields: lastName'")).isEqualTo(1);
        // Never the whole email, a code, a token or a value of a field
        assertThat(database.scalar("SELECT count(*) FROM guest_audit_log WHERE coalesce(email_masked, '') LIKE '%audited%'")).isZero();
        assertThat(database.scalar("SELECT count(*) FROM guest_audit_log WHERE coalesce(details, '') LIKE '%Məmmədova%'"
                + " OR coalesce(details, '') LIKE '%" + made.refreshToken() + "%'")).isZero();
    }

    @Test
    void theCaptchaHookIsAskedFirstAndCanRefuse() {
        java.util.List<String> asked = new java.util.ArrayList<>();
        captcha = (token, ip) -> {
            asked.add(token + "@" + ip);
            return reactor.core.publisher.Mono.just("pass".equals(token));
        };
        GuestAuthService guarded = service(0);
        assertThatThrownBy(() -> guarded.requestCode(new GuestDtos.OtpRequest("captcha@x.test", "fail"), "203.0.113.9").block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("CAPTCHA_FAILED"));
        verify(mailer, never()).sendGuestLoginCode(eq("captcha@x.test"), anyString(), anyInt());
        assertThat(database.scalar("SELECT count(*) FROM guest_email_otp WHERE email = 'captcha@x.test'")).isZero();
        guarded.requestCode(new GuestDtos.OtpRequest("captcha@x.test", "pass"), "203.0.113.9").block();
        mailedCode("captcha@x.test");
        assertThat(asked).containsExactly("fail@203.0.113.9", "pass@203.0.113.9");

        // The default of the configuration: off lets everything through, on without a provider refuses (fail closed)
        var config = new com.examportal.guest.captcha.GuestCaptchaConfig();
        assertThat(config.guestCaptcha(false).verify(null, null).block()).isTrue();
        assertThat(config.guestCaptcha(true).verify("anything", "203.0.113.9").block()).isFalse();
    }

    @Test
    void parallelProfileStepsMakeOneGuest() {
        // The same token twice at once, and a second token of the same email at the same time
        SignInResponse first = codeStep("parallel@x.test");
        SignInResponse second = codeStep("parallel@x.test");
        assertThat(second.status()).isEqualTo(GuestDtos.PROFILE_REQUIRED);
        List<SignInResponse> answers = reactor.core.publisher.Flux.merge(
                        service.completeProfile(profile(first.profileToken())).subscribeOn(reactor.core.scheduler.Schedulers.parallel()),
                        service.completeProfile(profile(first.profileToken())).subscribeOn(reactor.core.scheduler.Schedulers.parallel()),
                        service.completeProfile(profile(second.profileToken())).subscribeOn(reactor.core.scheduler.Schedulers.parallel()))
                .collectList().block();
        assertThat(answers).hasSize(3).extracting(answer -> answer.guest().id()).containsOnly(answers.get(0).guest().id());
        assertThat(database.scalar("SELECT count(*) FROM guest_participants WHERE email = 'parallel@x.test'")).isEqualTo(1);
    }

    @Test
    void theGuestsOwnDataAndRefresh() {
        SignInResponse made = service.completeProfile(profile(codeStep("own.data@x.test").profileToken())).block();
        GuestPrincipal principal = tokens.parse(made.accessToken());

        var changed = me.update(principal, new GuestDtos.UpdateMeRequest("Leyla", null, "+994 55 111 22 33")).block();
        assertThat(changed.firstName()).isEqualTo("Leyla");
        assertThat(changed.lastName()).isEqualTo("Quliyeva");
        assertThat(changed.phone()).isEqualTo("+994551112233");
        assertThat(changed.email()).isEqualTo("own.data@x.test");
        assertThat(changed.participantCode()).isEqualTo(made.guest().participantCode());
        assertThatThrownBy(() -> me.update(principal, new GuestDtos.UpdateMeRequest("L", null, null)).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("VALIDATION_ERROR"));

        SignInResponse refreshed = service.refresh(new GuestDtos.RefreshRequest(made.refreshToken())).block();
        assertThat(refreshed.refreshToken()).isNotEqualTo(made.refreshToken());
        assertThat(tokens.parse(refreshed.accessToken()).id()).isEqualTo(made.guest().id());
        service.logout(new GuestDtos.RefreshRequest(refreshed.refreshToken())).block();
        assertThatThrownBy(() -> service.refresh(new GuestDtos.RefreshRequest(refreshed.refreshToken())).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("REFRESH_TOKEN_INVALID"));
        List.of(made.accessToken(), refreshed.accessToken()).forEach(this::nothingSecretInTheLog);
    }
}
