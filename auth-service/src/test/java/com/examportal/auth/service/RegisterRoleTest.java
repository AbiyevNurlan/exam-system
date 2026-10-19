package com.examportal.auth.service;

import com.examportal.auth.client.GroupInviteClient;
import com.examportal.auth.mail.VerificationMailer;
import com.examportal.auth.repository.UserRepository;
import com.examportal.common.domain.dto.auth.RegisterRequest;
import com.examportal.common.domain.entity.User;
import com.examportal.common.domain.entity.UserRole;
import com.examportal.common.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B13: open registration can never make an INSTRUCTOR or an ADMIN. The request has no role field
 * (unknown fields are ignored) and the service stores STUDENT whatever the body says.
 */
class RegisterRoleTest {

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "INSTRUCTOR", "admin"})
    @SuppressWarnings("unchecked")
    void a_role_in_the_body_is_ignored_and_the_account_is_a_student(String role) throws Exception {
        String body = """
                {"email":"new@x.test","password":"Strong1pass","firstName":"Aysel","lastName":"Quliyeva",
                 "role":"%s","userRole":"%s","emailVerified":true}""".formatted(role, role);
        RegisterRequest request = new ObjectMapper().readValue(body, RegisterRequest.class);

        UserRepository users = mock(UserRepository.class);
        OtpService otp = mock(OtpService.class);
        TransactionalOperator tx = mock(TransactionalOperator.class);
        when(tx.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
        when(users.findByEmailForUpdate("new@x.test")).thenReturn(Mono.empty());
        when(users.save(any(User.class))).thenAnswer(call -> {
            User saved = call.getArgument(0);
            saved.setId(UUID.randomUUID());
            return Mono.just(saved);
        });
        when(otp.issue(any(UUID.class), anyString())).thenReturn(Mono.just("123456"));
        AuthService service = new AuthService(users, mock(TokenService.class), new BCryptPasswordEncoder(4),
                mock(JwtTokenProvider.class), tx, mock(GroupInviteClient.class), otp, mock(VerificationMailer.class),
                mock(AccountAuditLog.class));

        StepVerifier.create(service.register(request)).verifyComplete();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(UserRole.STUDENT);
        assertThat(saved.getValue().isEmailVerified()).isFalse();
        verify(otp).issue(eq(saved.getValue().getId()), eq(OtpService.PURPOSE_EMAIL_VERIFICATION));
    }
}
