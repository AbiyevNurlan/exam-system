package com.examportal.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

/**
 * Rate Limiting Configuration
 * Configures rate limiting strategies for the API Gateway.
 */
@Configuration
public class RateLimiterConfig {

    /**
     * Resolves the rate limiting key based on the client's IP address.
     */
    @Bean
    @Primary
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(
                exchange.getRequest()
                        .getRemoteAddress()
                        .getAddress()
                        .getHostAddress()
        );
    }

    /**
     * Resolves the rate limiting key based on the user's ID from JWT token.
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest()
                    .getHeaders()
                    .getFirst("X-User-Id");
            return Mono.just(userId != null ? userId : "anonymous");
        };
    }
}
