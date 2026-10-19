package com.examportal.exam.history;

import com.examportal.common.domain.dto.internal.UserLookupResponse.UserRef;
import com.examportal.common.exception.ValidationException;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.service.ParticipantDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;

/**
 * Builds the ChangeContext of a request that changes an exam (B12a): the actor with name and participant code
 * from auth-service (one lookup with its short timeout; the change never waits for or fails on it, the row then
 * has no name), the client address (the first X-Forwarded-For entry, set by the gateway, which believes it only
 * from its trusted proxies), the user agent, the gateway's X-Correlation-ID, and changeReason (3-500 characters
 * after trimming; empty = none; anything else is 400).
 */
@Component
@RequiredArgsConstructor
public class ChangeContextFactory {

    public static final int MIN_REASON = 3;
    public static final int MAX_REASON = 500;

    private final ParticipantDirectory directory;

    /**
     * reasons: where a reason may come from, in order of preference (the request body, then the query)
     */
    public Mono<ChangeContext> create(UserPrincipal principal, ServerHttpRequest request, String... reasons) {
        String reason = null;
        for (String candidate : reasons) {
            if (candidate != null && !candidate.isBlank()) {
                reason = candidate.trim();
                break;
            }
        }
        if (reason != null && (reason.length() < MIN_REASON || reason.length() > MAX_REASON)) {
            return Mono.error(new ValidationException("changeReason", "must be between " + MIN_REASON + " and "
                    + MAX_REASON + " characters"));
        }
        String finalReason = reason;
        UUID actorId = principal.getId();
        return directory.names(List.of(actorId))
                .map(users -> {
                    UserRef actor = users.get(actorId);
                    return new ChangeContext(actorId, principal.getRole(), ParticipantDirectory.fullName(actor),
                            actor != null ? actor.getParticipantCode() : null, clientIp(request),
                            request == null ? null : request.getHeaders().getFirst("User-Agent"),
                            request == null ? null : request.getHeaders().getFirst("X-Correlation-ID"),
                            finalReason, UUID.randomUUID());
                });
    }

    static String clientIp(ServerHttpRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null || remote.getAddress() == null ? null : remote.getAddress().getHostAddress();
    }
}
