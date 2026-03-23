package com.examportal.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Global Logging Filter
 * Logs all incoming requests and outgoing responses for monitoring and debugging.
 */
@Slf4j
@Component
public class LoggingFilter implements GlobalFilter, Ordered {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    private static final String START_TIME_ATTR = "startTime";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = exchange.getRequest()
                .getHeaders()
                .getFirst(CORRELATION_ID_HEADER);

        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }

        final String finalCorrelationId = correlationId;
        
        ServerHttpRequest mutatedRequest = exchange.getRequest()
                .mutate()
                .header(CORRELATION_ID_HEADER, finalCorrelationId)
                .build();

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .build();

        mutatedExchange.getAttributes().put(START_TIME_ATTR, System.currentTimeMillis());

        log.info("Incoming request: {} {} | Correlation-ID: {}",
                mutatedRequest.getMethod(),
                mutatedRequest.getURI().getPath(),
                finalCorrelationId);

        return chain.filter(mutatedExchange)
                .then(Mono.fromRunnable(() -> {
                    Long startTime = mutatedExchange.getAttribute(START_TIME_ATTR);
                    if (startTime != null) {
                        long duration = System.currentTimeMillis() - startTime;
                        log.info("Outgoing response: {} {} | Status: {} | Duration: {}ms | Correlation-ID: {}",
                                mutatedRequest.getMethod(),
                                mutatedRequest.getURI().getPath(),
                                mutatedExchange.getResponse().getStatusCode(),
                                duration,
                                finalCorrelationId);
                    }
                }));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
