package com.examportal.gateway.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * Fallback Controller
 * Handles fallback responses when downstream services are unavailable.
 *
 * The circuit breaker forwards the request here with its own method, so every method is answered
 * (a POST used to get 405), and the answer is 503 with Retry-After, not 200: a client must not take
 * "service unavailable" for a result. The body has the shape of every other error of the API.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    // Matches the open state of the circuit breakers (waitDurationInOpenState)
    private static final String RETRY_AFTER_SECONDS = "10";

    @RequestMapping(value = "/auth", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> authServiceFallback() {
        return Mono.just(createFallbackResponse("Auth Service", "Authentication service is temporarily unavailable"));
    }

    @RequestMapping(value = "/exam", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> examServiceFallback() {
        return Mono.just(createFallbackResponse("Exam Service", "Exam service is temporarily unavailable"));
    }

    @RequestMapping(value = "/monitoring", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> monitoringServiceFallback() {
        return Mono.just(createFallbackResponse("Monitoring Service", "Monitoring service is temporarily unavailable"));
    }

    private ResponseEntity<Map<String, Object>> createFallbackResponse(String serviceName, String message) {
        Map<String, Object> body = Map.of(
                "success", false,
                "message", message,
                "error", Map.of("code", "SERVICE_UNAVAILABLE", "description", message),
                "status", HttpStatus.SERVICE_UNAVAILABLE.value(),
                "service", serviceName,
                "timestamp", Instant.now().toString()
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(body);
    }
}
