package com.examportal.auth.controller;

import com.examportal.auth.repository.ParticipantCardRepository;
import com.examportal.auth.repository.ParticipantProfileRepository;
import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.participant.ParticipantCardDtos;
import com.examportal.common.exception.ResourceNotFoundException;
import com.examportal.common.security.InternalServiceAuth;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

/**
 * Internal Participant Card Controller (B11)
 * Service-to-service only: the account part of the admin's participant card for exam-service, which has
 * checked who may see it. Not routed by the gateway; the shared secret is required (404 without it).
 * Three statements by key, run at once: the account, its profile, its newest account events.
 */
@Hidden
@RestController
@RequestMapping("/internal/users/{userId}/card")
@RequiredArgsConstructor
public class InternalParticipantCardController {

    private final ParticipantCardRepository cardRepository;
    private final ParticipantProfileRepository profileRepository;

    @Value("${internal.service-secret:}")
    private String serviceSecret;

    @GetMapping
    public Mono<ApiResponse<ParticipantCardDtos.AuthCard>> card(
            @RequestHeader(name = InternalServiceAuth.HEADER, required = false) String secret,
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "20") int events) {

        if (!InternalServiceAuth.matches(secret, serviceSecret)) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
        int limit = Math.min(Math.max(events, 0), 100);
        return Mono.zip(
                        cardRepository.findAccount(userId)
                                .switchIfEmpty(Mono.error(new ResourceNotFoundException("User", String.valueOf(userId)))),
                        profileRepository.findByUserId(userId).map(Optional::of).defaultIfEmpty(Optional.empty()),
                        cardRepository.findAccountEvents(userId, limit))
                .map(found -> {
                    ParticipantCardDtos.AuthCard card = found.getT1();
                    card.setProfile(found.getT2().orElse(null));
                    card.setAccountEvents(found.getT3());
                    return ApiResponse.success(card);
                });
    }
}
