package com.examportal.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

/**
 * B13a: the guest paths of paid open exams pass the gateway without an account token, before the accounts' chain
 * (SecurityConfig, not changed). A guest token is signed with the guest key, which the gateway's JwtAuthenticationFilter
 * does not accept, so the gateway cannot check it: auth-service does, on every one of these paths (GuestSecurityConfig:
 * the sign-in steps open, /api/guest/** only with a guest token, 404 while PAID_ACCESS_ENABLED is false). The admin's
 * list of guests (/api/admin/guests/**) is not here: it needs an account token at the gateway like every admin path.
 * The rate limits of these paths are their own routes (auth-guest-otp, auth-guest).
 */
@Configuration
public class GuestGatewaySecurityConfig {

    // B13b: the public catalog and the payment notifications of paid open exams too; exam-service decides there
    // (PaidSecurityConfig: the catalog GET only, the webhook checks the provider's signature, the sandbox page its own link)
    public static final String[] GUEST_PATHS = {"/auth/guest/**", "/api/guest/**", "/api/public/paid-exams", "/api/public/paid-exams/**",
            "/api/payments/**"};

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityWebFilterChain guestGatewaySecurityWebFilterChain(ServerHttpSecurity http) {
        return http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers(GUEST_PATHS))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .build();
    }
}
