package com.examportal.gateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Fallback Controller
 * Handles fallback responses when downstream services are unavailable.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @GetMapping(value = "/auth", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> authServiceFallback() {
        return Mono.just(createFallbackResponse("Auth Service", "Authentication service is temporarily unavailable"));
    }

    @GetMapping(value = "/exam", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> examServiceFallback() {
        return Mono.just(createFallbackResponse("Exam Service", "Exam service is temporarily unavailable"));
    }

    @GetMapping(value = "/monitoring", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> monitoringServiceFallback() {
        return Mono.just(createFallbackResponse("Monitoring Service", "Monitoring service is temporarily unavailable"));
    }

    private Map<String, Object> createFallbackResponse(String serviceName, String message) {
        return Map.of(
                "status", HttpStatus.SERVICE_UNAVAILABLE.value(),
                "error", "Service Unavailable",
                "service", serviceName,
                "message", message,
                "timestamp", LocalDateTime.now().toString()
        );
    }
}
