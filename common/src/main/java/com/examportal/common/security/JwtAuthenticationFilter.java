package com.examportal.common.security;

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

/**
 * JWT Authentication Filter
 * Reactive web filter for JWT token validation and authentication.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements WebFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    
    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String token = extractToken(exchange.getRequest());

        if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
            try {
                Long userId = jwtTokenProvider.getUserIdFromToken(token);
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
                ServerHttpRequest mutatedRequest = exchange.getRequest()
                        .mutate()
                        .header("X-User-Id", userId.toString())
                        .header("X-User-Email", email)
                        .header("X-User-Role", role)
                        .build();

                ServerWebExchange mutatedExchange = exchange.mutate()
                        .request(mutatedRequest)
                        .build();

                return chain.filter(mutatedExchange)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
            } catch (Exception e) {
                log.error("Cannot set user authentication: {}", e.getMessage());
            }
        }

        return chain.filter(exchange);
    }

    private String extractToken(ServerHttpRequest request) {
        String bearerToken = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
