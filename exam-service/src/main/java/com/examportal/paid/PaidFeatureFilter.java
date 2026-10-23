package com.examportal.paid;

import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * B13b: while PAID_ACCESS_ENABLED is false every endpoint of paid open exams answers 404 FEATURE_DISABLED, before the
 * security chains (order -200; Spring Security is -100), so a token or its absence makes no difference. Every other
 * path is passed on untouched: the exams, audiences and sessions of today work as before.
 */
@Component
@Order(-200)
public class PaidFeatureFilter implements WebFilter {

    /** Every path of the paid module in exam-service */
    public static final List<String> PREFIXES = List.of("/api/public/paid-exams", "/api/guest/", "/api/payments/",
            "/api/admin/orders", "/api/admin/entitlements", "/api/admin/paid-exams");

    private final PaidFeature feature;

    public PaidFeatureFilter(PaidFeature feature) {
        this.feature = feature;
    }

    public static boolean isPaidPath(String path) {
        for (String prefix : PREFIXES) {
            if (prefix.endsWith("/") ? path.startsWith(prefix) : path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (feature.isEnabled() || !isPaidPath(path)) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"success\":false,\"message\":\"Not available\",\"error\":{\"code\":\"" + PaidErrors.FEATURE_DISABLED
                + "\",\"description\":\"Not available\"},\"timestamp\":\"" + Instant.now() + "\",\"path\":\""
                + path.replace("\\", "/").replace("\"", "") + "\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
