package com.examportal.paid.security;

import com.examportal.common.security.GuestTokens;
import com.examportal.common.security.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * B13b: the security chain of the open and guest paths of paid exams in exam-service, before the unchanged
 * SecurityConfig and only for these paths (securityMatcher):
 * - GET /api/public/paid-exams/** (the catalog) is open;
 * - POST /api/payments/webhook/{provider} is open (the provider's signature is checked by the handler) and so is the
 *   sandbox's own payment page (/api/payments/sandbox/**, only with PAYMENTS_SANDBOX_ALLOWED and a signed link);
 * - /api/guest/** only with a guest token (ROLE_GUEST): account tokens of any role are 403;
 * - any other path under the matcher is refused.
 * The admin's paths (/api/admin/orders, /entitlements, /paid-exams) stay in SecurityConfig: ADMIN.
 */
@Configuration
public class PaidSecurityConfig {

    public static final String[] MATCHED_PATHS = {"/api/public/paid-exams", "/api/public/paid-exams/**", "/api/guest/**", "/api/payments/**"};

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityWebFilterChain paidSecurityWebFilterChain(ServerHttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter,
                                                             @Value("${security.jwt.secret-key:}") String jwtSecret) {
        return chain(http, jwtAuthenticationFilter, new PaidGuestAuthenticationFilter(GuestTokens.signingKey(jwtSecret)));
    }

    /** The chain itself, also for PaidEndpointRegistryTest */
    public static SecurityWebFilterChain chain(ServerHttpSecurity http, WebFilter accountFilter, WebFilter guestFilter) {
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
                        .pathMatchers(HttpMethod.GET, "/api/public/paid-exams", "/api/public/paid-exams/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/payments/webhook/*").permitAll()
                        .pathMatchers("/api/payments/sandbox/**").permitAll()
                        .pathMatchers("/api/guest/**").hasRole("GUEST")
                        .anyExchange().denyAll())
                .addFilterAt(accountFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .addFilterAfter(guestFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

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
