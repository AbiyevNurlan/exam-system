package com.examportal.exam.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;

import java.time.Duration;

/**
 * Resilience Configuration
 * Circuit Breaker pattern for fault tolerance and system stability.
 * Prevents cascading failures in high-load scenarios.
 */
@Slf4j
@Configuration
public class ResilienceConfig {

    /**
     * Circuit Breaker for database operations
     * Opens after 50% failure rate with minimum 10 calls
     * Protects system from database overload
     */
    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> databaseCircuitBreakerCustomizer() {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowSize(100)
                        .minimumNumberOfCalls(10)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .permittedNumberOfCallsInHalfOpenState(5)
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .recordExceptions(Exception.class)
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(10))
                        .build())
                .build());
    }

    /**
     * Circuit Breaker Registry with monitoring
     */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(100)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(5)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);

        // Event listeners for monitoring
        registry.circuitBreaker("database").getEventPublisher()
                .onStateTransition(event -> log.warn("Database Circuit Breaker state changed: {}", event))
                .onError(event -> log.error("Database Circuit Breaker error: {}", event))
                .onSuccess(event -> log.debug("Database Circuit Breaker success"));

        registry.circuitBreaker("redis").getEventPublisher()
                .onStateTransition(event -> log.warn("Redis Circuit Breaker state changed: {}", event))
                .onError(event -> log.error("Redis Circuit Breaker error: {}", event))
                .onSuccess(event -> log.debug("Redis Circuit Breaker success"));

        return registry;
    }
}
