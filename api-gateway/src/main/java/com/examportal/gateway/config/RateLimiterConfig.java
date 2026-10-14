package com.examportal.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
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
     * The bucket of a request: its route and its client address ("auth-login:203.0.113.7").
     *
     * The Redis rate limiter keys its buckets by this value alone (request_rate_limiter.{key}),
     * not by route, so a key of the IP only made every route of an address share one bucket: a
     * few code checks (5 tokens each) emptied it for registration and sign-in too, and exam calls
     * counted against /auth. With the route in the key each route has its own bucket with its own
     * capacity.
     */
    @Bean
    @Primary
    public KeyResolver routeAndIpKeyResolver(ClientIpResolver clientIpResolver) {
        return exchange -> {
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            String routeId = route != null ? route.getId() : "no-route";
            return Mono.just(routeId + ":" + clientIpResolver.resolve(exchange.getRequest()));
        };
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
