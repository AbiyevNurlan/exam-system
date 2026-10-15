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
                        
                        // Service-to-service endpoints: no JWT, the controller checks the shared secret
                        // (InternalServiceAuth). The gateway does not route /internal/**.
                        .pathMatchers("/internal/**").permitAll()

                        // Group administration - ADMIN for every group, INSTRUCTOR for the own ones; which
                        // group is decided by GroupAccessPolicy (another owner's group is a 404)
                        .pathMatchers("/api/admin/groups", "/api/admin/groups/**").hasAnyRole("ADMIN", "INSTRUCTOR")
                        // Every other administration path - admin only, here by path as well as by @PreAuthorize
                        .pathMatchers("/api/admin/**").hasRole("ADMIN")
                        // Everything below a group (bulk membership, join link, join requests) - as above;
                        // adding by filter is ADMIN only (@PreAuthorize on the method)
                        .pathMatchers("/api/groups/*/**").hasAnyRole("ADMIN", "INSTRUCTOR")
                        // Joining a group with a link: what the link is for may be asked without a token
                        // (name of the group only); joining itself needs one
                        .pathMatchers(HttpMethod.GET, "/api/join/*").permitAll()
                        .pathMatchers("/api/join/**").authenticated()
                        // Group names for choosing an exam audience - read only
                        .pathMatchers(HttpMethod.GET, "/api/groups").hasAnyRole("ADMIN", "INSTRUCTOR")

                        // Media upload and management - admin and instructors; participants get media
                        // only through their session (/api/exams/session/{sessionId}/media/{mediaId})
                        .pathMatchers("/api/media/**").hasAnyRole("ADMIN", "INSTRUCTOR")
                        // Exam management - only admin and instructors
                        .pathMatchers(HttpMethod.POST, "/api/exams").hasAnyRole("ADMIN", "INSTRUCTOR")
                        // Audience preview of groups that are not stored yet - whoever may set an audience
                        .pathMatchers(HttpMethod.POST, "/api/exams/audience/preview").hasAnyRole("ADMIN", "INSTRUCTOR")
                        // Grading overview (pending exams, badge count) - admin and instructors; the service
                        // limits an instructor to the own exams
                        .pathMatchers("/api/grading/**").hasAnyRole("ADMIN", "INSTRUCTOR")
                        .pathMatchers(HttpMethod.PUT, "/api/exams/**").hasAnyRole("ADMIN", "INSTRUCTOR")
                        .pathMatchers(HttpMethod.DELETE, "/api/exams/**").hasAnyRole("ADMIN", "INSTRUCTOR")
                        .pathMatchers("/api/exams/my").hasAnyRole("ADMIN", "INSTRUCTOR")
                        .pathMatchers("/api/exams/*/publish", "/api/exams/*/archive").hasAnyRole("ADMIN", "INSTRUCTOR")
                        .pathMatchers("/api/exams/*/results", "/api/exams/*/results/**").hasAnyRole("ADMIN", "INSTRUCTOR")
                        // Live monitoring and exam analytics - admin and instructors (the service then
                        // limits an instructor to the own exams); platform analytics - admin only
                        .pathMatchers("/api/exams/monitoring/**", "/api/exams/*/monitoring",
                                "/api/exams/session/*/violations", "/api/exams/*/analytics/**").hasAnyRole("ADMIN", "INSTRUCTOR")
                        .pathMatchers("/api/analytics/**").hasRole("ADMIN")
                        
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
