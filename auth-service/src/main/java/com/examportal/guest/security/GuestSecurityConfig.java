package com.examportal.guest.security;

import com.examportal.common.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * B13a item 4: the security chain of the guest paths, before the accounts' chain (SecurityConfig, which is not
 * changed) and only for these paths (securityMatcher):
 * - the sign-in steps (POST /auth/guest/**) and the feature switch are open;
 * - /api/guest/** only with a guest token (ROLE_GUEST): an account's token, of any role, is 403 there;
 * - the admin's list of guests (/api/admin/guests/**) only for ADMIN, with an account token as everywhere;
 * - any other path under the matcher is refused.
 * A guest token is read only by GuestAuthenticationFilter here; every other chain of every service sees no token.
 */
@Configuration
public class GuestSecurityConfig {

    public static final String[] MATCHED_PATHS = {"/auth/guest/**", "/api/guest/**", "/api/admin/guests", "/api/admin/guests/**"};
    public static final String[] OPEN_SIGN_IN_STEPS = {"/auth/guest/otp/request", "/auth/guest/otp/verify",
            "/auth/guest/profile", "/auth/guest/refresh", "/auth/guest/logout"};

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityWebFilterChain guestSecurityWebFilterChain(ServerHttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter,
                                                              GuestTokenProvider guestTokens) {
        return chain(http, jwtAuthenticationFilter, new GuestAuthenticationFilter(guestTokens));
    }

    /** The chain itself, also for GuestEndpointRegistryTest with its own filters */
    public static SecurityWebFilterChain chain(ServerHttpSecurity http, org.springframework.web.server.WebFilter accountFilter,
                                               org.springframework.web.server.WebFilter guestFilter) {
        return http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers(MATCHED_PATHS))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((exchange, denied) -> error(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"))
                        .accessDeniedHandler((exchange, denied) -> error(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN")))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.GET, "/api/guest/feature").permitAll()
                        .pathMatchers(HttpMethod.POST, OPEN_SIGN_IN_STEPS).permitAll()
                        .pathMatchers("/api/guest/**").hasRole("GUEST")
                        .pathMatchers("/api/admin/guests", "/api/admin/guests/**").hasRole("ADMIN")
                        .anyExchange().denyAll())
                .addFilterAt(accountFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .addFilterAfter(guestFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    // The body format of every other error (ApiResponse with error.code)
    private static Mono<Void> error(ServerWebExchange exchange, HttpStatus status, String code) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"success\":false,\"message\":\"" + status.getReasonPhrase() + "\",\"error\":{\"code\":\"" + code
                + "\",\"description\":\"" + status.getReasonPhrase() + "\"},\"timestamp\":\"" + Instant.now() + "\",\"path\":\""
                + exchange.getRequest().getPath().value().replace("\\", "/").replace("\"", "") + "\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
