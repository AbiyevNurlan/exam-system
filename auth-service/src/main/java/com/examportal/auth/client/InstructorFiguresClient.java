package com.examportal.auth.client;

import com.examportal.common.domain.dto.instructor.InstructorDtos;
import com.examportal.common.security.InternalServiceAuth;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * B13: what each teacher owns in exam-service (groups, members, exams, exams ACTIVE or SCHEDULED), over its internal
 * endpoint with the shared service secret. Empty Optional when exam-service cannot be asked (not configured, down,
 * slower than 2 seconds): the list of teachers still works without the figures, and a deactivation then needs the
 * confirmation as if the teacher had running exams.
 */
@Slf4j
@Component
public class InstructorFiguresClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final WebClient webClient;
    private final String serviceSecret;

    public InstructorFiguresClient(WebClient.Builder webClientBuilder,
                                   @Value("${services.exam.url}") String examServiceUrl,
                                   @Value("${internal.service-secret:}") String serviceSecret) {
        this.webClient = webClientBuilder.baseUrl(examServiceUrl).build();
        this.serviceSecret = serviceSecret;
    }

    public Mono<Optional<Map<UUID, InstructorDtos.Figures>>> figures(Collection<UUID> instructorIds) {
        if (instructorIds.isEmpty()) {
            return Mono.just(Optional.of(Map.of()));
        }
        if (!InternalServiceAuth.isConfigured(serviceSecret)) {
            return Mono.just(Optional.empty());
        }
        return webClient.post()
                .uri("/internal/instructors/figures")
                .header(InternalServiceAuth.HEADER, serviceSecret)
                .bodyValue(InstructorDtos.FiguresRequest.builder().ids(List.copyOf(instructorIds)).build())
                .retrieve()
                .bodyToMono(InstructorDtos.FiguresResponse.class)
                .timeout(TIMEOUT)
                .map(response -> Optional.of(response.getFigures() == null ? Map.<UUID, InstructorDtos.Figures>of()
                        : response.getFigures().stream().collect(Collectors.toMap(InstructorDtos.Figures::getId,
                                Function.identity(), (a, b) -> a))))
                .onErrorResume(e -> {
                    log.warn("Teacher figures could not be loaded from exam-service: {}", e.getClass().getSimpleName());
                    return Mono.just(Optional.empty());
                });
    }
}
