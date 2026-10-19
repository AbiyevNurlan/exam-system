package com.examportal.auth.service;

import com.examportal.auth.client.GroupInviteClient;
import com.examportal.auth.mail.VerificationMailer;
import com.examportal.auth.repository.UserRepository;
import com.examportal.common.domain.dto.auth.AuthResponse;
import com.examportal.common.domain.dto.auth.ChangePasswordRequest;
import com.examportal.common.domain.dto.auth.LoginRequest;
import com.examportal.common.domain.dto.auth.RefreshTokenRequest;
import com.examportal.common.domain.entity.RefreshToken;
import com.examportal.common.domain.entity.User;
import com.examportal.common.domain.entity.UserRole;
import com.examportal.common.exception.ApplicationException;
import com.examportal.common.security.JwtTokenProvider;
import com.examportal.common.security.TokenRevocationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B13: a teacher whose password an administrator set signs in with mustChangePassword true and a token with the
 * claim pwc; after 7 days the temporary password no longer works (TEMPORARY_PASSWORD_EXPIRED, only after the right
 * password); a refresh never gives a deactivated account a token and takes the claim from the database; changing
 * the password ends every access token issued before (TokenRevocationStore) and answers a normal pair.
 */
class TemporaryPasswordTest {

    private static final UUID TEACHER = UUID.randomUUID();
    private static final String TEMPORARY = "Tmp-Pass-1234567";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final UserRepository users = mock(UserRepository.class);
    private final TokenService tokenService = mock(TokenService.class);
    private final JwtTokenProvider jwt = mock(JwtTokenProvider.class);
    private final TokenRevocationStore revocations = mock(TokenRevocationStore.class);
    private final GroupInviteClient invites = mock(GroupInviteClient.class);
    private AuthService service;
    private User teacher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TransactionalOperator tx = mock(TransactionalOperator.class);
        when(tx.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
        teacher = User.builder().email("teacher@x.test").passwordHash(encoder.encode(TEMPORARY)).firstName("Aysel")
                .lastName("Quliyeva").role(UserRole.INSTRUCTOR).emailVerified(true).mustChangePassword(true)
                .tempPasswordExpiresAt(LocalDateTime.now().plusDays(7)).build();
        teacher.setId(TEACHER);
        teacher.setActive(true);
        when(users.findByEmail("teacher@x.test")).thenReturn(Mono.just(teacher));
        when(users.findById(TEACHER)).thenReturn(Mono.just(teacher));
        when(users.findByIdForUpdate(TEACHER)).thenReturn(Mono.just(teacher));
        when(users.resetFailedLoginAttempts(TEACHER)).thenReturn(Mono.just(1));
        when(users.touchLastLogin(TEACHER)).thenReturn(Mono.just(1));
        when(users.updatePasswordHash(eq(TEACHER), anyString())).thenReturn(Mono.just(1));
        when(users.registerFailedLogin(anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(Mono.just(false));
        when(invites.applyForVerifiedUser(any())).thenReturn(Mono.empty());
        when(tokenService.createRefreshToken(TEACHER)).thenReturn(Mono.just(RefreshToken.builder().token("refresh").userId(TEACHER).build()));
        when(tokenService.revokeAllUserTokens(TEACHER)).thenReturn(Mono.empty());
        when(jwt.generateAccessToken(eq(TEACHER), anyString(), anyString(), anyBoolean()))
                .thenAnswer(call -> (boolean) call.getArgument(3) ? "access-pwc" : "access");
        when(revocations.revokeAllIssuedBefore(eq(TEACHER), any())).thenReturn(Mono.empty());
        AccountAuditLog audit = mock(AccountAuditLog.class);
        when(audit.record(any(), anyString())).thenReturn(Mono.empty());
        service = new AuthService(users, tokenService, encoder, jwt, tx, invites, mock(OtpService.class),
                mock(VerificationMailer.class), audit, revocations);
    }

    private static LoginRequest login(String password) {
        LoginRequest request = new LoginRequest();
        request.setEmail("teacher@x.test");
        request.setPassword(password);
        return request;
    }

    @Test
    void a_temporary_password_signs_in_with_the_flag_and_a_restricted_token() {
        AuthResponse response = service.login(login(TEMPORARY)).block();
        assertThat(response.isMustChangePassword()).isTrue();
        assertThat(response.getUser().isMustChangePassword()).isTrue();
        assertThat(response.getAccessToken()).isEqualTo("access-pwc");
        verify(users).touchLastLogin(TEACHER);
    }

    @Test
    void after_seven_days_the_temporary_password_no_longer_works() {
        teacher.setTempPasswordExpiresAt(LocalDateTime.now().minusMinutes(1));
        StepVerifier.create(service.login(login(TEMPORARY)))
                .expectErrorSatisfies(e -> assertThat(((ApplicationException) e).getErrorCode()).isEqualTo("TEMPORARY_PASSWORD_EXPIRED"))
                .verify();
        // A wrong password still says only that the credentials are wrong
        StepVerifier.create(service.login(login("Wrong-Pass-123")))
                .expectErrorSatisfies(e -> assertThat(((ApplicationException) e).getErrorCode()).isNotEqualTo("TEMPORARY_PASSWORD_EXPIRED"))
                .verify();
        verify(users, never()).touchLastLogin(TEACHER);
    }

    @Test
    void changing_the_password_ends_the_old_tokens_and_answers_a_normal_pair() {
        AuthResponse response = service.changePassword(TEACHER, ChangePasswordRequest.builder()
                .currentPassword(TEMPORARY).newPassword("Own-Pass-98765").build()).block();
        assertThat(response.isMustChangePassword()).isFalse();
        assertThat(response.getAccessToken()).isEqualTo("access");
        verify(tokenService).revokeAllUserTokens(TEACHER);
        verify(revocations).revokeAllIssuedBefore(eq(TEACHER), any());
    }

    @Test
    void a_new_password_equal_to_the_temporary_one_is_refused() {
        StepVerifier.create(service.changePassword(TEACHER, ChangePasswordRequest.builder()
                        .currentPassword(TEMPORARY).newPassword(TEMPORARY).build()))
                .expectErrorSatisfies(e -> assertThat(((ApplicationException) e).getErrorCode()).isEqualTo("PASSWORD_UNCHANGED"))
                .verify();
        verify(revocations, never()).revokeAllIssuedBefore(any(), any());
    }

    @Test
    void a_refresh_gives_a_deactivated_account_nothing_and_takes_the_claim_from_the_database() {
        when(tokenService.validateRefreshToken("refresh")).thenReturn(Mono.just(RefreshToken.builder().token("refresh").userId(TEACHER).build()));
        when(tokenService.rotateRefreshToken("refresh", TEACHER)).thenReturn(Mono.just(RefreshToken.builder().token("refresh-2").userId(TEACHER).build()));
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("refresh");
        assertThat(service.refreshToken(request).block().getAccessToken()).isEqualTo("access-pwc");

        teacher.setActive(false);
        StepVerifier.create(service.refreshToken(request))
                .expectErrorSatisfies(e -> assertThat(((ApplicationException) e).getStatus().value()).isEqualTo(401))
                .verify();
    }
}
