package com.examportal.exam.controller;

import com.examportal.common.domain.dto.internal.ApplyGroupInvitesRequest;
import com.examportal.common.domain.dto.internal.ApplyGroupInvitesResponse;
import com.examportal.common.security.InternalServiceAuth;
import com.examportal.exam.service.GroupInviteService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Internal Group Invite Controller
 * Service-to-service only: auth-service reports a user with a verified email so that pending CSV
 * invites for that address become memberships. Not routed by the gateway; every call must carry the
 * shared secret, and without a configured secret the endpoint answers 404 like an unknown path.
 */
@Hidden
@RestController
@RequestMapping("/internal/group-invites")
@RequiredArgsConstructor
public class InternalGroupInviteController {

    private final GroupInviteService inviteService;

    @Value("${internal.service-secret:}")
    private String serviceSecret;

    @PostMapping("/apply")
    public Mono<ApplyGroupInvitesResponse> apply(
            @RequestHeader(name = InternalServiceAuth.HEADER, required = false) String secret,
            @Valid @RequestBody ApplyGroupInvitesRequest request) {

        if (!InternalServiceAuth.matches(secret, serviceSecret)) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
        return inviteService.applyPendingInvites(request.getUserId(), request.getEmail())
                .map(applied -> ApplyGroupInvitesResponse.builder().applied(applied).build());
    }
}