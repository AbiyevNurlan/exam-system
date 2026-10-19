package com.examportal.exam.controller;

import com.examportal.common.domain.dto.instructor.InstructorDtos;
import com.examportal.common.security.InternalServiceAuth;
import com.examportal.exam.repository.InstructorLimitsRepository;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Internal Instructor Controller (B13)
 * Service-to-service only: auth-service asks what each teacher owns (groups, members, exams, exams that are ACTIVE or
 * SCHEDULED) for the administrator's list of teachers and before a deactivation. Not routed by the gateway; every
 * call must carry the shared secret, and without one the endpoint answers 404 like an unknown path.
 */
@Hidden
@RestController
@RequestMapping("/internal/instructors")
@RequiredArgsConstructor
public class InternalInstructorController {

    private final InstructorLimitsRepository repository;

    @Value("${internal.service-secret:}")
    private String serviceSecret;

    @PostMapping("/figures")
    public Mono<InstructorDtos.FiguresResponse> figures(
            @RequestHeader(name = InternalServiceAuth.HEADER, required = false) String secret,
            @Valid @RequestBody InstructorDtos.FiguresRequest request) {

        if (!InternalServiceAuth.matches(secret, serviceSecret)) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
        if (request.getIds().isEmpty()) {
            return Mono.just(InstructorDtos.FiguresResponse.builder().figures(java.util.List.of()).build());
        }
        return repository.figures(request.getIds()).collectList()
                .map(figures -> InstructorDtos.FiguresResponse.builder().figures(figures).build());
    }
}
