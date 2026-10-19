package com.examportal.auth.config;

import com.examportal.common.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

/**
 * Security Configuration for Auth Service
 * Configures Spring Security with reactive WebFlux security.
 */
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges
                        // Public endpoints
                        .pathMatchers(HttpMethod.POST, "/auth/register").permitAll()
                        .pathMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        .pathMatchers(HttpMethod.POST, "/auth/refresh").permitAll()
                        .pathMatchers(HttpMethod.POST, "/auth/forgot-password").permitAll()
                        .pathMatchers(HttpMethod.POST, "/auth/reset-password").permitAll()
                        .pathMatchers(HttpMethod.POST, "/auth/verify-otp").permitAll()
                        .pathMatchers(HttpMethod.POST, "/auth/resend-otp").permitAll()
                        .pathMatchers(HttpMethod.GET, "/auth/health").permitAll()
                        // Actuator endpoints
                        .pathMatchers("/actuator/**").permitAll()
                        // API documentation
                        .pathMatchers("/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Service-to-service endpoints: no JWT, the controller checks the shared secret
                        // (InternalServiceAuth). The gateway does not route /internal/**.
                        .pathMatchers("/internal/**").permitAll()
                        // Admin API: enforced here by path so it does not depend on method security
                        .pathMatchers("/auth/admin/**").hasRole("ADMIN")
                        // B13: teachers made and managed by administrators (InstructorAdminController)
                        .pathMatchers("/api/admin/**").hasRole("ADMIN")
                        // All other requests require authentication
                        .anyExchange().authenticated()
                )
                .addFilterAt(jwtAuthenticationFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
