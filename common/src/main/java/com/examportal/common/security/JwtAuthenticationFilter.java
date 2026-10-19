package com.examportal.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * JWT Authentication Filter
 * Reactive web filter for JWT token validation and authentication, the same in the gateway and in every service.
 *
 * B13, in this one place:
 * - a token whose user had the tokens revoked after it was issued (TokenRevocationStore: deactivation, a password
 *   reset by an administrator, a password change) counts as invalid: the request goes on unauthenticated (401 on
 *   a protected endpoint);
 * - a token with the claim "pwc" (a temporary password set by an administrator) reaches only POST
 *   /auth/change-password and POST /auth/logout; everything else is 403 PASSWORD_CHANGE_REQUIRED.
 */
@Slf4j
@Component
public class JwtAuthenticationFilter implements WebFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_EMAIL_HEADER = "X-User-Email";
    private static final String USER_ROLE_HEADER = "X-User-Role";
    private static final List<String> IDENTITY_HEADERS = List.of(USER_ID_HEADER, USER_EMAIL_HEADER, USER_ROLE_HEADER);

    /** What a token of a temporary password may reach (B13) */
    static final Set<String> PASSWORD_CHANGE_PATHS = Set.of("/auth/change-password", "/auth/logout");
    static final String PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED";

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectProvider<TokenRevocationStore> revocationStore;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, ObjectProvider<TokenRevocationStore> revocationStore) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.revocationStore = revocationStore;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String token = extractToken(exchange.getRequest());

        // Identity headers are only ever set here from a valid token, never taken from the client
        ServerWebExchange sanitizedExchange = exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .headers(headers -> IDENTITY_HEADERS.forEach(headers::remove))
                        .build())
                .build();

        if (!StringUtils.hasText(token)) {
            return chain.filter(sanitizedExchange);
        }
        Claims claims;
        UUID userId;
        try {
            claims = jwtTokenProvider.parseClaims(token);
            userId = subject(claims);
        } catch (JwtException | IllegalArgumentException e) {
            // Fall through unauthenticated: protected endpoints then answer 401 instead of 500
            log.warn("Rejected JWT token: {}", e.getClass().getSimpleName());
            return chain.filter(sanitizedExchange);
        }
        String email = claims.get("email", String.class);
        String role = claims.get("role", String.class);
        boolean passwordChangeRequired = Boolean.TRUE.equals(claims.get(JwtTokenProvider.PASSWORD_CHANGE_CLAIM, Boolean.class));

        TokenRevocationStore store = revocationStore.getIfAvailable();
        Mono<Boolean> revoked = store == null ? Mono.just(false) : store.isRevoked(userId, claims.getIssuedAt());
        return revoked.flatMap(isRevoked -> {
            if (isRevoked) {
                log.info("Rejected a revoked token of user {}", userId);
                return chain.filter(sanitizedExchange);
            }
            if (passwordChangeRequired && !PASSWORD_CHANGE_PATHS.contains(exchange.getRequest().getPath().value())) {
                return passwordChangeRequired(exchange);
            }
            UserPrincipal userPrincipal = UserPrincipal.builder()
                    .id(userId)
                    .email(email)
                    .role(role)
                    .active(true)
                    .emailVerified(true)
                    .build();

            Authentication authentication = new UsernamePasswordAuthenticationToken(
                    userPrincipal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role))
            );

            // Add user info headers for downstream services
            ServerHttpRequest mutatedRequest = sanitizedExchange.getRequest()
                    .mutate()
                    .header(USER_ID_HEADER, userId.toString())
                    .header(USER_EMAIL_HEADER, email)
                    .header(USER_ROLE_HEADER, role)
                    .build();

            ServerWebExchange mutatedExchange = sanitizedExchange.mutate()
                    .request(mutatedRequest)
                    .build();

            return chain.filter(mutatedExchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
        });
    }

    /**
     * The user id from the token subject. Anything but a canonical UUID is rejected with IllegalArgumentException,
     * which counts as an invalid token (as JwtTokenProvider.getUserIdFromToken).
     */
    private static UUID subject(Claims claims) {
        String subject = claims.getSubject();
        if (subject == null) {
            throw new IllegalArgumentException("Token subject is missing");
        }
        UUID userId = UUID.fromString(subject);
        if (!userId.toString().equalsIgnoreCase(subject)) {
            throw new IllegalArgumentException("Token subject is not a UUID");
        }
        return userId;
    }

    // 403 in the body format of every other error (ApiResponse with error.code)
    private static Mono<Void> passwordChangeRequired(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"success\":false,\"message\":\"The temporary password must be changed first\","
                + "\"error\":{\"code\":\"" + PASSWORD_CHANGE_REQUIRED + "\",\"description\":\"Only POST /auth/change-password "
                + "and POST /auth/logout are allowed with a temporary password\"},\"timestamp\":\"" + Instant.now()
                + "\",\"path\":\"" + exchange.getRequest().getPath().value().replace("\\", "/").replace("\"", "") + "\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private String extractToken(ServerHttpRequest request) {
        String bearerToken = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
