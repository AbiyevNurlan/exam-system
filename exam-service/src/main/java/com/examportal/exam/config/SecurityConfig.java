package com.examportal.exam.config;

import com.examportal.common.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import reactor.core.publisher.Mono;

/**
 * Security Configuration for Exam Service
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
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint((exchange, ex) -> {
                            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                            return Mono.empty();
                        })
                        .accessDeniedHandler((exchange, denied) -> {
                            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                            return Mono.empty();
                        })
                )
                .authorizeExchange(exchanges -> exchanges
                        // Public endpoints
                        .pathMatchers("/actuator/**").permitAll()
                        .pathMatchers("/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        
                        // Group administration - admin only, enforced here by path as well as by @PreAuthorize
                        .pathMatchers("/api/admin/**").hasRole("ADMIN")

                        // Exam management - only admin and instructors
                        .pathMatchers(HttpMethod.POST, "/api/exams").hasAnyRole("ADMIN", "INSTRUCTOR")
                        .pathMatchers(HttpMethod.PUT, "/api/exams/**").hasAnyRole("ADMIN", "INSTRUCTOR")
                        .pathMatchers(HttpMethod.DELETE, "/api/exams/**").hasAnyRole("ADMIN", "INSTRUCTOR")
                        .pathMatchers("/api/exams/my").hasAnyRole("ADMIN", "INSTRUCTOR")
                        .pathMatchers("/api/exams/*/publish", "/api/exams/*/archive").hasAnyRole("ADMIN", "INSTRUCTOR")
                        .pathMatchers("/api/exams/*/results").hasAnyRole("ADMIN", "INSTRUCTOR")
                        
                        // Exam participation - all authenticated users
                        .pathMatchers("/api/exams/available").authenticated()
                        .pathMatchers("/api/exams/*/start").authenticated()
                        .pathMatchers("/api/exams/session/**").authenticated()
                        .pathMatchers(HttpMethod.GET, "/api/exams/**").authenticated()
                        
                        // All other requests require authentication
                        .anyExchange().authenticated()
                )
                .addFilterAt(jwtAuthenticationFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
