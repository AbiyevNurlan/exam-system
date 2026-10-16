package com.examportal.auth.controller;

import com.examportal.auth.service.ParticipantProfileService;
import com.examportal.common.domain.dto.ApiResponse;
import com.examportal.common.domain.dto.internal.ProfileWriteRequest;
import com.examportal.common.domain.dto.profile.ParticipantProfileDtos;
import com.examportal.common.exception.ValidationException;
import com.examportal.common.security.InternalServiceAuth;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Internal Profile Controller (B11)
 * Service-to-service only: exam-service reads and writes participant profiles here for GET/PUT
 * /api/me/profile and the admin's participant card. Not routed by the gateway; every call must carry
 * the shared secret, and without one the endpoint answers 404. Errors are the usual ApiResponse
 * (400 VALIDATION_ERROR with the field, 404 RESOURCE_NOT_FOUND), which exam-service passes on.
 */
@Hidden
@RestController
@RequestMapping("/internal/users/{userId}/profile")
@RequiredArgsConstructor
public class InternalProfileController {

    private final ParticipantProfileService profileService;

    @Value("${internal.service-secret:}")
    private String serviceSecret;

    @GetMapping
    public Mono<ApiResponse<ParticipantProfileDtos.MyProfile>> get(
            @RequestHeader(name = InternalServiceAuth.HEADER, required = false) String secret,
            @PathVariable UUID userId) {

        if (!InternalServiceAuth.matches(secret, serviceSecret)) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
        return profileService.mine(userId).map(ApiResponse::success);
    }

    @PutMapping
    public Mono<ApiResponse<ParticipantProfileDtos.Profile>> put(
            @RequestHeader(name = InternalServiceAuth.HEADER, required = false) String secret,
            @PathVariable UUID userId,
            @RequestBody ProfileWriteRequest request) {

        if (!InternalServiceAuth.matches(secret, serviceSecret)) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
        if (request == null || request.getActorId() == null) {
            return Mono.error(new ValidationException("actorId", "is required"));
        }
        return profileService.update(userId, request.getProfile(), request.getActorId(), request.isPartial()).map(ApiResponse::success);
    }
}
