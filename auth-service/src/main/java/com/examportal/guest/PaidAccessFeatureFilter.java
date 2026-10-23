package com.examportal.guest;

import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * B13a: while PAID_ACCESS_ENABLED is false every guest endpoint answers 404 FEATURE_DISABLED, before the security
 * chains (order -200, Spring Security is -100), so neither a token nor its absence makes a difference. The one
 * exception is GET /api/guest/feature, which tells the frontend the switch (200 either way). Every other path is
 * passed on untouched.
 */
@Component
@Order(-200)
public class PaidAccessFeatureFilter implements WebFilter {

    public static final String FEATURE_PATH = "/api/guest/feature";

    private final GuestFeature feature;

    public PaidAccessFeatureFilter(GuestFeature feature) {
        this.feature = feature;
    }

    /** The paths of the guest feature: the sign-in, the guest's own data, the admin's list of guests */
    public static boolean isGuestPath(String path) {
        return path.startsWith("/auth/guest/") || path.equals("/auth/guest")
                || path.startsWith("/api/guest/") || path.equals("/api/guest")
                || path.startsWith("/api/admin/guests/") || path.equals("/api/admin/guests");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (feature.isEnabled() || !isGuestPath(path)
                || (HttpMethod.GET.equals(exchange.getRequest().getMethod()) && FEATURE_PATH.equals(path))) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"success\":false,\"message\":\"Not available\",\"error\":{\"code\":\"" + GuestErrors.FEATURE_DISABLED
                + "\",\"description\":\"Not available\"},\"timestamp\":\"" + Instant.now() + "\",\"path\":\""
                + path.replace("\\", "/").replace("\"", "") + "\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
