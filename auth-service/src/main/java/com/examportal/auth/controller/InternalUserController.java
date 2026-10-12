package com.examportal.auth.controller;

import com.examportal.auth.repository.UserRepository;
import com.examportal.common.domain.dto.internal.UserLookupRequest;
import com.examportal.common.domain.dto.internal.UserLookupResponse;
import com.examportal.common.domain.entity.User;
import com.examportal.common.security.InternalServiceAuth;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Internal User Controller
 * Service-to-service only: lets exam-service resolve emails and ids to users, because the users
 * table lives in this service's database. Not routed by the gateway; every call must carry the
 * shared secret, and without a configured secret the endpoint answers 404 like an unknown path.
 */
@Hidden
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserRepository userRepository;

    @Value("${internal.service-secret:}")
    private String serviceSecret;

    @PostMapping("/lookup")
    public Mono<UserLookupResponse> lookup(
            @RequestHeader(name = InternalServiceAuth.HEADER, required = false) String secret,
            @Valid @RequestBody UserLookupRequest request) {

        if (!InternalServiceAuth.matches(secret, serviceSecret)) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }

        List<String> emails = request.getEmails() == null ? List.of() : request.getEmails().stream()
                .filter(Objects::nonNull)
                .map(email -> email.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        List<UUID> ids = request.getIds() == null ? List.of() : request.getIds().stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Flux<User> byEmail = emails.isEmpty() ? Flux.empty() : userRepository.findActiveByEmailIn(emails);
        Flux<User> byId = ids.isEmpty() ? Flux.empty() : userRepository.findActiveByIdIn(ids);

        return Flux.concat(byEmail, byId)
                .distinct(User::getId)
                .map(user -> UserLookupResponse.UserRef.builder()
                        .id(user.getId())
                        .email(user.getEmail())
                        .firstName(user.getFirstName())
                        .lastName(user.getLastName())
                        .emailVerified(user.isEmailVerified())
                        .build())
                .collectList()
                .map(users -> UserLookupResponse.builder().users(users).build());
    }
}