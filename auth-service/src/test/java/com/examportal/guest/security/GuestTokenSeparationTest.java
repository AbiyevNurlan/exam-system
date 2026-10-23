package com.examportal.guest.security;

import com.examportal.common.security.GuestTokens;
import com.examportal.common.security.JwtAuthenticationFilter;
import com.examportal.common.security.JwtTokenProvider;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * B13a item 4 without a database: a guest token is signed with the guest key, lives 30 minutes and carries role GUEST
 * and the participant code; the accounts' JwtTokenProvider and JwtAuthenticationFilter (of every service and the
 * gateway) do not accept it, so it signs nobody in there; an account token is not a guest token; a token signed with
 * the guest key but of another issuer, role or type is refused.
 */
class GuestTokenSeparationTest {

    static final String SECRET = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef-test-secret".getBytes());

    private GuestTokenProvider guests;
    private JwtTokenProvider accounts;

    @BeforeEach
    void setUp() {
        guests = new GuestTokenProvider(SECRET, 30);
        accounts = new JwtTokenProvider();
        ReflectionTestUtils.setField(accounts, "secretKey", SECRET);
        ReflectionTestUtils.setField(accounts, "accessTokenExpiration", 900_000L);
        ReflectionTestUtils.setField(accounts, "issuer", "exam-system");
    }

    @Test
    void aGuestTokenIsReadOnlyWithTheGuestKey() {
        UUID id = UUID.randomUUID();
        String token = guests.issueAccessToken(id, 4_141_592);

        GuestPrincipal guest = guests.parse(token);
        assertThat(guest.id()).isEqualTo(id);
        assertThat(guest.participantCode()).isEqualTo(4_141_592);
        assertThat(guests.accessTokenLifetime()).isEqualTo(Duration.ofMinutes(30));

        // The accounts' provider: the signature does not match
        assertThatThrownBy(() -> accounts.parseClaims(token)).isInstanceOf(io.jsonwebtoken.security.SignatureException.class);
        assertThat(accounts.validateToken(token)).isFalse();
    }

    @Test
    void anAccountTokenIsNotAGuestToken() {
        String student = accounts.generateAccessToken(UUID.randomUUID(), "s@x.test", "STUDENT");
        String admin = accounts.generateAccessToken(UUID.randomUUID(), "a@x.test", "ADMIN");
        assertThatThrownBy(() -> guests.parse(student)).isInstanceOf(io.jsonwebtoken.security.SignatureException.class);
        assertThatThrownBy(() -> guests.parse(admin)).isInstanceOf(io.jsonwebtoken.security.SignatureException.class);
    }

    @Test
    void theGuestKeyAloneIsNotEnough() {
        var key = GuestTokens.signingKey(SECRET);
        Date now = new Date();
        Date later = new Date(now.getTime() + 60_000);
        String otherIssuer = Jwts.builder().subject(UUID.randomUUID().toString()).issuer("exam-system").issuedAt(now).expiration(later)
                .claim("role", "GUEST").claim("type", "GUEST_ACCESS").claim("code", 1_000_000).signWith(key).compact();
        String otherRole = Jwts.builder().subject(UUID.randomUUID().toString()).issuer(GuestTokens.ISSUER).issuedAt(now).expiration(later)
                .claim("role", "ADMIN").claim("type", "GUEST_ACCESS").claim("code", 1_000_000).signWith(key).compact();
        String otherType = Jwts.builder().subject(UUID.randomUUID().toString()).issuer(GuestTokens.ISSUER).issuedAt(now).expiration(later)
                .claim("role", "GUEST").claim("type", "REFRESH").claim("code", 1_000_000).signWith(key).compact();
        String expired = Jwts.builder().subject(UUID.randomUUID().toString()).issuer(GuestTokens.ISSUER).issuedAt(new Date(now.getTime() - 120_000))
                .expiration(new Date(now.getTime() - 60_000)).claim("role", "GUEST").claim("type", "GUEST_ACCESS").claim("code", 1_000_000)
                .signWith(key).compact();
        for (String token : new String[]{otherIssuer, otherRole, otherType, expired}) {
            assertThatThrownBy(() -> guests.parse(token)).isInstanceOf(io.jsonwebtoken.JwtException.class);
        }
    }

    @Test
    void theAccountsFilterSignsNobodyInWithAGuestToken() {
        @SuppressWarnings("unchecked")
        ObjectProvider<com.examportal.common.security.TokenRevocationStore> none = mock(ObjectProvider.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(accounts, none);
        String token = guests.issueAccessToken(UUID.randomUUID(), 1_234_567);

        AtomicReference<Authentication> seen = new AtomicReference<>();
        AtomicReference<String> roleHeader = new AtomicReference<>("unset");
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/exams/available")
                .header("Authorization", "Bearer " + token)
                .header("X-User-Role", "GUEST"));
        filter.filter(exchange, through -> {
            roleHeader.set(through.getRequest().getHeaders().getFirst("X-User-Role"));
            return ReactiveSecurityContextHolder.getContext().map(SecurityContext::getAuthentication)
                    .doOnNext(seen::set).then(Mono.empty());
        }).block();

        assertThat(seen.get()).isNull();
        // A header the client sent is removed, never trusted
        assertThat(roleHeader.get()).isNull();
    }
}
