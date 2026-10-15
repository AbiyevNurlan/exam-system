package com.examportal.auth.service;

import com.examportal.auth.client.GroupInviteClient;
import com.examportal.auth.mail.VerificationMailer;
import com.examportal.auth.repository.UserRepository;
import com.examportal.common.domain.dto.auth.ChangePasswordRequest;
import com.examportal.common.domain.dto.auth.UpdateProfileRequest;
import com.examportal.common.domain.entity.RefreshToken;
import com.examportal.common.domain.entity.User;
import com.examportal.common.domain.entity.UserRole;
import com.examportal.common.exception.ApplicationException;
import com.examportal.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B9: GET /auth/me, PUT /auth/profile and POST /auth/change-password act on the user of the token only.
 */
class AuthServiceAccountTest {

    private static final UUID USER = UUID.randomUUID();
    private static final String CURRENT = "Current1pass";

    // Strength 4: real BCrypt, fast enough for a unit test
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final TokenService tokenService = mock(TokenService.class);
    private final JwtTokenProvider jwt = mock(JwtTokenProvider.class);
    private final TransactionalOperator transactionalOperator = mock(TransactionalOperator.class);
    private final AccountAuditLog auditLog = mock(AccountAuditLog.class);

    private AuthService service;
    private User user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(transactionalOperator.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
        user = User.builder().email("me@x.test").passwordHash(encoder.encode(CURRENT)).firstName("Aysel")
                .lastName("Quliyeva").role(UserRole.STUDENT).emailVerified(true).build();
        user.setId(USER);
        user.setActive(true);
        user.setCreatedAt(LocalDateTime.of(2026, 1, 2, 3, 4));
        when(userRepository.findById(USER)).thenReturn(Mono.just(user));
        when(userRepository.findByIdForUpdate(USER)).thenReturn(Mono.just(user));
        when(userRepository.updatePasswordHash(eq(USER), anyString())).thenReturn(Mono.just(1));
        when(tokenService.revokeAllUserTokens(USER)).thenReturn(Mono.empty());
        when(tokenService.createRefreshToken(USER)).thenReturn(Mono.just(RefreshToken.builder().token("new-refresh").userId(USER).build()));
        when(jwt.generateAccessToken(eq(USER), anyString(), anyString())).thenReturn("new-access");
        when(auditLog.record(eq(USER), anyString())).thenReturn(Mono.empty());
        service = new AuthService(userRepository, tokenService, encoder, jwt, transactionalOperator,
                mock(GroupInviteClient.class), mock(OtpService.class), mock(VerificationMailer.class), auditLog);
    }

    private static ChangePasswordRequest change(String current, String next) {
        return ChangePasswordRequest.builder().currentPassword(current).newPassword(next).build();
    }

    private static void assertCode(Throwable error, HttpStatus status, String code) {
        assertThat(error).isInstanceOf(ApplicationException.class);
        assertThat(((ApplicationException) error).getStatus()).isEqualTo(status);
        assertThat(((ApplicationException) error).getErrorCode()).isEqualTo(code);
    }

    private void nothingWasChanged() {
        verify(userRepository, never()).updatePasswordHash(any(), anyString());
        verify(tokenService, never()).revokeAllUserTokens(any());
        verify(auditLog, never()).record(any(), anyString());
    }

    // ---------- GET /auth/me ----------

    @Test
    void meIsTheUserOfTheTokenWithoutThePasswordHash() {
        StepVerifier.create(service.currentUser(USER))
                .assertNext(me -> {
                    assertThat(me.getId()).isEqualTo(USER);
                    assertThat(me.getEmail()).isEqualTo("me@x.test");
                    assertThat(me.getRole()).isEqualTo("STUDENT");
                    assertThat(me.isEmailVerified()).isTrue();
                    assertThat(me.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 1, 2, 3, 4));
                    assertThat(me.toString()).doesNotContain(user.getPasswordHash());
                })
                .verifyComplete();
    }

    @Test
    void meOfADeactivatedOrGoneUserIs401() {
        user.setActive(false);
        StepVerifier.create(service.currentUser(USER)).expectErrorSatisfies(e -> assertCode(e, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN")).verify();
        UUID gone = UUID.randomUUID();
        when(userRepository.findById(gone)).thenReturn(Mono.empty());
        StepVerifier.create(service.currentUser(gone)).expectErrorSatisfies(e -> assertCode(e, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN")).verify();
    }

    // ---------- PUT /auth/profile ----------

    @Test
    void profileIsTrimmedAnEmptyPhoneIsRemovedAndAudited() {
        when(userRepository.updateProfile(USER, "Leyla", "Məmmədova", null)).thenReturn(Mono.just(1));
        UpdateProfileRequest request = UpdateProfileRequest.builder().firstName("  Leyla ").lastName("Məmmədova ").phone("  ").build();

        StepVerifier.create(service.updateProfile(USER, request)).expectNextCount(1).verifyComplete();

        verify(userRepository).updateProfile(USER, "Leyla", "Məmmədova", null);
        verify(auditLog).record(USER, AccountAuditLog.PROFILE_UPDATED);
    }

    @Test
    void theProfilePhoneIsStoredAsE164() {
        when(userRepository.updateProfile(USER, "Leyla", "Mammadova", "+994501234567")).thenReturn(Mono.just(1));
        UpdateProfileRequest request = UpdateProfileRequest.builder().firstName("Leyla").lastName("Mammadova").phone("050 123 45 67").build();

        StepVerifier.create(service.updateProfile(USER, request)).expectNextCount(1).verifyComplete();

        verify(userRepository).updateProfile(USER, "Leyla", "Mammadova", "+994501234567");
    }

    @Test
    void aForeignPhoneIs400AndNothingChanges() {
        UpdateProfileRequest request = UpdateProfileRequest.builder().firstName("Leyla").lastName("Mammadova").phone("+7 912 345 67 89").build();
        StepVerifier.create(service.updateProfile(USER, request))
                .expectErrorSatisfies(e -> assertCode(e, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR"))
                .verify();
        verify(userRepository, never()).updateProfile(any(), anyString(), anyString(), any());
    }

    @Test
    void aNameOfOneCharacterAfterTrimmingIs400() {
        UpdateProfileRequest request = UpdateProfileRequest.builder().firstName(" L ").lastName("Məmmədova").build();
        StepVerifier.create(service.updateProfile(USER, request)).expectErrorSatisfies(e -> assertCode(e, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR")).verify();
        verify(userRepository, never()).updateProfile(any(), anyString(), anyString(), any());
    }

    @Test
    void profileOfAnInactiveAccountIsNotChanged() {
        when(userRepository.updateProfile(eq(USER), anyString(), anyString(), isNull())).thenReturn(Mono.just(0));
        UpdateProfileRequest request = UpdateProfileRequest.builder().firstName("Leyla").lastName("Əliyeva").build();
        StepVerifier.create(service.updateProfile(USER, request)).expectErrorSatisfies(e -> assertCode(e, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN")).verify();
        verify(auditLog, never()).record(any(), anyString());
    }

    // ---------- POST /auth/change-password ----------

    @Test
    void aWrongCurrentPasswordIsRefusedAndNothingChanges() {
        StepVerifier.create(service.changePassword(USER, change("Wrong1pass", "Brand2New")))
                .expectErrorSatisfies(e -> assertCode(e, HttpStatus.BAD_REQUEST, "PASSWORD_CHANGE_REJECTED"))
                .verify();
        nothingWasChanged();
    }

    @Test
    void aDeactivatedOrGoneAccountGetsExactlyTheSameAnswer() {
        String wrongPassword = service.changePassword(USER, change("Wrong1pass", "Brand2New"))
                .materialize().block().getThrowable().getMessage();

        user.setActive(false);
        Throwable inactive = service.changePassword(USER, change(CURRENT, "Brand2New")).materialize().block().getThrowable();
        UUID gone = UUID.randomUUID();
        when(userRepository.findByIdForUpdate(gone)).thenReturn(Mono.empty());
        Throwable missing = service.changePassword(gone, change(CURRENT, "Brand2New")).materialize().block().getThrowable();

        assertCode(inactive, HttpStatus.BAD_REQUEST, "PASSWORD_CHANGE_REJECTED");
        assertCode(missing, HttpStatus.BAD_REQUEST, "PASSWORD_CHANGE_REJECTED");
        assertThat(inactive.getMessage()).isEqualTo(wrongPassword);
        assertThat(missing.getMessage()).isEqualTo(wrongPassword);
        nothingWasChanged();
    }

    @Test
    void theSamePasswordAgainIs400PasswordUnchanged() {
        StepVerifier.create(service.changePassword(USER, change(CURRENT, CURRENT)))
                .expectErrorSatisfies(e -> assertCode(e, HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED"))
                .verify();
        nothingWasChanged();
    }

    @Test
    void aChangeStoresTheNewHashRevokesEverySessionAuditsAndGivesANewPair() {
        StepVerifier.create(service.changePassword(USER, change(CURRENT, "Brand2New")))
                .assertNext(tokens -> {
                    assertThat(tokens.getAccessToken()).isEqualTo("new-access");
                    assertThat(tokens.getRefreshToken()).isEqualTo("new-refresh");
                })
                .verifyComplete();

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(userRepository).updatePasswordHash(eq(USER), hash.capture());
        assertThat(encoder.matches("Brand2New", hash.getValue())).isTrue();
        assertThat(hash.getValue()).doesNotContain("Brand2New");
        verify(tokenService).revokeAllUserTokens(USER);
        verify(auditLog).record(USER, AccountAuditLog.PASSWORD_CHANGED);
        // The new refresh token is created after the old ones were revoked
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(tokenService);
        order.verify(tokenService).revokeAllUserTokens(USER);
        order.verify(tokenService).createRefreshToken(USER);
    }
}
