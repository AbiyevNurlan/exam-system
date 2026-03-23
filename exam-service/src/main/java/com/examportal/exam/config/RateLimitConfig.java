package com.examportal.exam.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate Limiting Configuration
 * Protects system from overload and DDoS attacks.
 * 100,000 concurrent users support with intelligent throttling.
 */
@Slf4j
@Configuration
public class RateLimitConfig {

    private final ConcurrentHashMap<String, Bucket> cache = new ConcurrentHashMap<>();

    /**
     * Global rate limit: 1000 requests per minute per IP
     * For 100K users: allows burst traffic while preventing abuse
     */
    @Bean
    public WebFilter rateLimitFilter() {
        return (ServerWebExchange exchange, WebFilterChain chain) -> {
            String clientIp = getClientIp(exchange);
            String path = exchange.getRequest().getPath().value();

            // Skip rate limiting for health checks and public endpoints
            if (path.startsWith("/actuator") || path.startsWith("/swagger-ui")) {
                return chain.filter(exchange);
            }

            Bucket bucket = resolveBucket(clientIp, path);
            
            if (bucket.tryConsume(1)) {
                return chain.filter(exchange);
            } else {
                log.warn("Rate limit exceeded for IP: {} on path: {}", clientIp, path);
                exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                exchange.getResponse().getHeaders().add("X-Rate-Limit-Retry-After-Seconds", "60");
                return exchange.getResponse().setComplete();
            }
        };
    }

    private Bucket resolveBucket(String clientIp, String path) {
        return cache.computeIfAbsent(clientIp, k -> createBucket(path));
    }

    private Bucket createBucket(String path) {
        Bandwidth limit;
        
        if (path.startsWith("/api/exams/session/")) {
            // During exam: 200 requests per minute (1 request every 300ms)
            limit = Bandwidth.builder()
                    .capacity(200)
                    .refillGreedy(200, Duration.ofMinutes(1))
                    .build();
        } else if (path.startsWith("/api/exams") && path.contains("/start")) {
            // Starting exam: 10 attempts per minute (prevent rapid restarts)
            limit = Bandwidth.builder()
                    .capacity(10)
                    .refillGreedy(10, Duration.ofMinutes(1))
                    .build();
        } else {
            // General API: 1000 requests per minute
            limit = Bandwidth.builder()
                    .capacity(1000)
                    .refillGreedy(1000, Duration.ofMinutes(1))
                    .build();
        }
        
        return Bucket.builder().addLimit(limit).build();
    }

    private String getClientIp(ServerWebExchange exchange) {
        String ip = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) {
            ip = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
        }
        if (ip == null || ip.isEmpty()) {
            ip = exchange.getRequest().getRemoteAddress() != null 
                    ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() 
                    : "unknown";
        }
        return ip;
    }
}
