package com.examportal.auth.controller;

import com.examportal.auth.repository.AdminAuditQueryRepository;
import com.examportal.common.domain.dto.internal.AdminAuditQuery;
import com.examportal.common.security.InternalServiceAuth;
import io.swagger.v3.oas.annotations.Hidden;
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
 * Internal Audit Controller
 * Service-to-service only: the admin_audit_log entries for the one audit list that exam-service
 * serves to administrators. Not routed by the gateway; every call must carry the shared secret,
 * and without one the endpoint answers 404.
 */
@Hidden
@RestController
@RequestMapping("/internal/audit")
@RequiredArgsConstructor
public class InternalAuditController {

    private final AdminAuditQueryRepository auditRepository;

    @Value("${internal.service-secret:}")
    private String serviceSecret;

    @PostMapping("/admin")
    public Mono<AdminAuditQuery.Response> adminEntries(
            @RequestHeader(name = InternalServiceAuth.HEADER, required = false) String secret,
            @RequestBody AdminAuditQuery.Request request) {

        if (!InternalServiceAuth.matches(secret, serviceSecret)) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
        return auditRepository.find(request);
    }
}
