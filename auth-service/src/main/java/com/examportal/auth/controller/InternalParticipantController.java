package com.examportal.auth.controller;

import com.examportal.auth.repository.ParticipantQueryRepository;
import com.examportal.common.domain.dto.internal.ParticipantQuery;
import com.examportal.common.security.InternalServiceAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Internal Participant Controller
 * Service-to-service only: the participant list, the ids matching a filter and the summary counts
 * for exam-service (which owns the groups and serves the admin screens). Not routed by the
 * gateway; every call must carry the shared secret, and without one the endpoint answers 404.
 *
 * A group filter arrives as a list of user ids, which can be far larger than the 256 KB the JSON
 * codec accepts. The body is therefore read here, with its own limit, and only after the secret
 * was checked; the limit of every public endpoint stays as it is.
 */
@Hidden
@RestController
@RequestMapping("/internal/users/participants")
@RequiredArgsConstructor
public class InternalParticipantController {

    // About 400 000 user ids as JSON
    private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    private final ParticipantQueryRepository participantRepository;
    private final ObjectMapper objectMapper;

    @Value("${internal.service-secret:}")
    private String serviceSecret;

    @PostMapping
    public Mono<ParticipantQuery.PageResponse> page(
            @RequestHeader(name = InternalServiceAuth.HEADER, required = false) String secret,
            ServerHttpRequest request) {

        return read(secret, request, ParticipantQuery.PageRequest.class).flatMap(participantRepository::findPage);
    }

    @PostMapping("/ids")
    public Mono<ParticipantQuery.IdsResponse> ids(
            @RequestHeader(name = InternalServiceAuth.HEADER, required = false) String secret,
            ServerHttpRequest request) {

        return read(secret, request, ParticipantQuery.IdsRequest.class).flatMap(participantRepository::findIds);
    }

    @PostMapping("/summary")
    public Mono<ParticipantQuery.SummaryResponse> summary(
            @RequestHeader(name = InternalServiceAuth.HEADER, required = false) String secret,
            ServerHttpRequest request) {

        return read(secret, request, ParticipantQuery.SummaryRequest.class).flatMap(participantRepository::summary);
    }

    private <T> Mono<T> read(String secret, ServerHttpRequest request, Class<T> type) {
        if (!InternalServiceAuth.matches(secret, serviceSecret)) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
        return DataBufferUtils.join(request.getBody(), MAX_BODY_BYTES)
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return objectMapper.readValue(bytes, type);
                    } catch (java.io.IOException e) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST)));
    }
}
