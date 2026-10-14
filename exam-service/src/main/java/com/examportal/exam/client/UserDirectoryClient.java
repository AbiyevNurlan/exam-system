package com.examportal.exam.client;

import com.examportal.common.domain.dto.internal.AdminAuditQuery;
import com.examportal.common.domain.dto.internal.ParticipantQuery;
import com.examportal.common.exception.ValidationException;
import com.examportal.common.domain.dto.internal.UserLookupRequest;
import com.examportal.common.domain.dto.internal.UserLookupResponse;
import com.examportal.common.domain.dto.internal.UserLookupResponse.UserRef;
import com.examportal.common.domain.dto.internal.UserSearchRequest;
import com.examportal.common.domain.dto.internal.UserStatsRequest;
import com.examportal.common.domain.dto.internal.UserStatsResponse;
import com.examportal.common.security.InternalServiceAuth;
import com.examportal.exam.exception.GroupException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * User Directory Client
 * Users live in auth-service's database, so emails and user ids are resolved through its internal
 * endpoint (POST /internal/users/lookup), authenticated with the shared service secret.
 * Any failure, including a missing secret, surfaces as 503 USER_DIRECTORY_UNAVAILABLE: nothing is
 * ever added to a group on a guess.
 */
@Component
public class UserDirectoryClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final Duration LARGE_TIMEOUT = Duration.ofSeconds(15);
    private static final int LARGE_BODY_BYTES = 16 * 1024 * 1024;

    private final WebClient webClient;
    private final WebClient largeBodyClient;
    private final String serviceSecret;

    public UserDirectoryClient(WebClient.Builder webClientBuilder,
                               @Value("${services.auth.url}") String authServiceUrl,
                               @Value("${internal.service-secret:}") String serviceSecret) {
        this.webClient = webClientBuilder.baseUrl(authServiceUrl).build();
        // Lists of user ids (group filters, bulk membership) do not fit the default 256 KB answer buffer
        this.largeBodyClient = webClientBuilder.clone()
                .baseUrl(authServiceUrl)
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(LARGE_BODY_BYTES))
                .build();
        this.serviceSecret = serviceSecret;
    }

    /**
     * The active users among the given emails (normalized) and ids; at most
     * UserLookupRequest.MAX_ITEMS of each per call
     */
    public Mono<List<UserRef>> lookup(List<String> emails, List<UUID> ids) {
        if (!InternalServiceAuth.isConfigured(serviceSecret)) {
            return Mono.error(GroupException.userDirectoryUnavailable(
                    new IllegalStateException("INTERNAL_SERVICE_SECRET is not configured")));
        }
        return webClient.post()
                .uri("/internal/users/lookup")
                .header(InternalServiceAuth.HEADER, serviceSecret)
                .bodyValue(UserLookupRequest.builder().emails(emails).ids(ids).build())
                .retrieve()
                .bodyToMono(UserLookupResponse.class)
                .timeout(TIMEOUT)
                .map(response -> response.getUsers() != null ? response.getUsers() : List.<UserRef>of())
                // Only the exception type is kept: the original message may echo the request
                .onErrorMap(e -> !(e instanceof GroupException), e -> GroupException.userDirectoryUnavailable(
                        new IllegalStateException(e.getClass().getSimpleName())));
    }

    /**
     * The active users whose first name, last name, full name or email starts with q (ignoring
     * case), or whose id is q; at most limit of them (auth-service allows up to 200).
     * POST /internal/users/search; fails like lookup.
     */
    public Mono<List<UserRef>> search(String q, int limit) {
        if (!InternalServiceAuth.isConfigured(serviceSecret)) {
            return Mono.error(GroupException.userDirectoryUnavailable(
                    new IllegalStateException("INTERNAL_SERVICE_SECRET is not configured")));
        }

        return webClient.post()
                .uri("/internal/users/search")
                .header(InternalServiceAuth.HEADER, serviceSecret)
                .bodyValue(UserSearchRequest.builder().q(q).limit(limit).build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<UserRef>>() {
                })
                .timeout(TIMEOUT)
                .defaultIfEmpty(List.of())
                // Only the exception type is kept: the original message may echo the request
                .onErrorMap(e -> !(e instanceof GroupException), e -> GroupException.userDirectoryUnavailable(
                        new IllegalStateException(e.getClass().getSimpleName())));
    }

    /**
     * Aggregate user counts and the registrations per day of the last `days` days: numbers only.
     * POST /internal/users/stats; fails like lookup.
     */
    public Mono<UserStatsResponse> stats(int days) {
        if (!InternalServiceAuth.isConfigured(serviceSecret)) {
            return Mono.error(GroupException.userDirectoryUnavailable(
                    new IllegalStateException("INTERNAL_SERVICE_SECRET is not configured")));
        }

        return webClient.post()
                .uri("/internal/users/stats")
                .header(InternalServiceAuth.HEADER, serviceSecret)
                .bodyValue(UserStatsRequest.builder().days(days).build())
                .retrieve()
                .bodyToMono(UserStatsResponse.class)
                .timeout(TIMEOUT)
                .onErrorMap(e -> !(e instanceof GroupException), e -> GroupException.userDirectoryUnavailable(
                        new IllegalStateException(e.getClass().getSimpleName())));
    }

    /**
     * One page of participants (users with the role STUDENT) matching the filter, with the total.
     * POST /internal/users/participants; fails like lookup.
     */
    public Mono<ParticipantQuery.PageResponse> participants(ParticipantQuery.PageRequest request) {
        return post("/internal/users/participants", request, ParticipantQuery.PageResponse.class);
    }

    /**
     * How many participants match the filter, and the ids of at most request.limit of them
     */
    public Mono<ParticipantQuery.IdsResponse> participantIds(ParticipantQuery.IdsRequest request) {
        return post("/internal/users/participants/ids", request, ParticipantQuery.IdsResponse.class);
    }

    public Mono<ParticipantQuery.SummaryResponse> participantSummary(ParticipantQuery.SummaryRequest request) {
        return post("/internal/users/participants/summary", request, ParticipantQuery.SummaryResponse.class);
    }

    /**
     * The entries of auth-service's admin_audit_log matching the request (count and the newest ones)
     */
    public Mono<AdminAuditQuery.Response> adminAudit(AdminAuditQuery.Request request) {
        return post("/internal/audit/admin", request, AdminAuditQuery.Response.class);
    }

    /**
     * A call whose request or answer may carry many user ids: sent with the client that accepts large bodies
     */
    private <T> Mono<T> post(String uri, Object body, Class<T> type) {
        if (!InternalServiceAuth.isConfigured(serviceSecret)) {
            return Mono.error(GroupException.userDirectoryUnavailable(
                    new IllegalStateException("INTERNAL_SERVICE_SECRET is not configured")));
        }
        return largeBodyClient.post()
                .uri(uri)
                .header(InternalServiceAuth.HEADER, serviceSecret)
                .bodyValue(body)
                .retrieve()
                // A refused filter (for example a search text that is too short) is the caller's error, not an outage
                .onStatus(status -> status.value() == 400, response -> Mono.error(
                        new ValidationException("The participant filter was not accepted")))
                .bodyToMono(type)
                .timeout(LARGE_TIMEOUT)
                .onErrorMap(e -> !(e instanceof GroupException) && !(e instanceof ValidationException),
                        e -> GroupException.userDirectoryUnavailable(new IllegalStateException(e.getClass().getSimpleName())));
    }

    public Mono<UserRef> findById(UUID userId) {
        return lookup(List.of(), List.of(userId)).flatMap(users -> Mono.justOrEmpty(users.stream().findFirst()));
    }

    public Mono<UserRef> findByEmail(String normalizedEmail) {
        return lookup(List.of(normalizedEmail), List.of())
                .flatMap(users -> Mono.justOrEmpty(users.stream().findFirst()));
    }
}