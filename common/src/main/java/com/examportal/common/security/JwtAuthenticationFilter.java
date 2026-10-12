package com.examportal.common.security;

import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
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

import java.util.List;
import java.util.UUID;

/**
 * JWT Authentication Filter
 * Reactive web filter for JWT token validation and authentication.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements WebFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_EMAIL_HEADER = "X-User-Email";
    private static final String USER_ROLE_HEADER = "X-User-Role";
    private static final List<String> IDENTITY_HEADERS = List.of(USER_ID_HEADER, USER_EMAIL_HEADER, USER_ROLE_HEADER);

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String token = extractToken(exchange.getRequest());

        // Identity headers are only ever set here from a valid token, never taken from the client
        ServerWebExchange sanitizedExchange = exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .headers(headers -> IDENTITY_HEADERS.forEach(headers::remove))
                        .build())
                .build();

        if (StringUtils.hasText(token)) {
            try {
                if (jwtTokenProvider.validateToken(token)) {
                    UUID userId = jwtTokenProvider.getUserIdFromToken(token);
                    String email = jwtTokenProvider.getEmailFromToken(token);
                    String role = jwtTokenProvider.getRoleFromToken(token);

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
                }
            } catch (JwtException | IllegalArgumentException e) {
                // Fall through unauthenticated: protected endpoints then answer 401 instead of 500
                log.warn("Rejected JWT token: {}", e.getClass().getSimpleName());
            }
        }

        return chain.filter(sanitizedExchange);
    }

    private String extractToken(ServerHttpRequest request) {
        String bearerToken = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
