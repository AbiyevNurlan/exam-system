package com.examportal.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B13, the one filter of the gateway and every service: a token of a temporary password reaches only the password
 * change and the sign-out (403 PASSWORD_CHANGE_REQUIRED with the usual error body elsewhere); a token issued before
 * its user's tokens were revoked is unauthenticated; a normal token is authenticated as before; a client cannot
 * set the identity headers.
 */
class JwtAuthenticationFilterTest {

    private static final UUID USER = UUID.randomUUID();

    private final JwtTokenProvider tokens = new JwtTokenProvider();
    private final TokenRevocationStore store = mock(TokenRevocationStore.class);
    private JwtAuthenticationFilter filter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ReflectionTestUtils.setField(tokens, "secretKey", Base64.getEncoder().encodeToString(new byte[32]));
        ReflectionTestUtils.setField(tokens, "accessTokenExpiration", 900_000L);
        ReflectionTestUtils.setField(tokens, "issuer", "exam-system");
        ObjectProvider<TokenRevocationStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        when(store.isRevoked(eq(USER), any())).thenReturn(Mono.just(false));
        filter = new JwtAuthenticationFilter(tokens, provider);
    }

    private record Passed(boolean called, SecurityContext context, MockServerWebExchange exchange) {
    }

    private Passed run(String method, String path, String token) {
        MockServerHttpRequest request = MockServerHttpRequest.method(org.springframework.http.HttpMethod.valueOf(method), path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-User-Role", "ADMIN")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<SecurityContext> context = new AtomicReference<>();
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        WebFilterChain chain = passed -> {
            called.set(true);
            assertThat(passed.getRequest().getHeaders().getFirst("X-User-Role")).isNotEqualTo("ADMIN");
            return ReactiveSecurityContextHolder.getContext().doOnNext(context::set).then();
        };
        filter.filter(exchange, chain).block();
        return new Passed(called.get(), context.get(), exchange);
    }

    @Test
    void a_normal_token_is_authenticated() {
        Passed passed = run("GET", "/api/exams/my", tokens.generateAccessToken(USER, "t@x.test", "INSTRUCTOR"));
        assertThat(passed.called()).isTrue();
        assertThat(((UserPrincipal) passed.context().getAuthentication().getPrincipal()).getRole()).isEqualTo("INSTRUCTOR");
    }

    @Test
    void a_temporary_password_reaches_only_the_password_change_and_the_sign_out() {
        String token = tokens.generateAccessToken(USER, "t@x.test", "INSTRUCTOR", true);
        for (String path : new String[]{"/api/exams/my", "/auth/me", "/api/admin/groups", "/auth/profile"}) {
            Passed passed = run("GET", path, token);
            assertThat(passed.called()).as(path).isFalse();
            assertThat(passed.exchange().getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(passed.exchange().getResponse().getBodyAsString().block()).contains("\"code\":\"PASSWORD_CHANGE_REQUIRED\"");
        }
        for (String path : new String[]{"/auth/change-password", "/auth/logout"}) {
            Passed passed = run("POST", path, token);
            assertThat(passed.called()).as(path).isTrue();
            assertThat(passed.context().getAuthentication()).isNotNull();
        }
    }

    @Test
    void a_revoked_token_is_not_authenticated() {
        when(store.isRevoked(eq(USER), any())).thenReturn(Mono.just(true));
        Passed passed = run("GET", "/api/exams/my", tokens.generateAccessToken(USER, "t@x.test", "INSTRUCTOR"));
        assertThat(passed.called()).isTrue();
        assertThat(passed.context()).isNull();
    }

    @Test
    void a_tampered_token_is_not_authenticated() {
        String token = tokens.generateAccessToken(USER, "t@x.test", "INSTRUCTOR");
        Passed passed = run("GET", "/api/exams/my", token.substring(0, token.length() - 2) + "xx");
        assertThat(passed.called()).isTrue();
        assertThat(passed.context()).isNull();
    }
}
