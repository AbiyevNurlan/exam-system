package com.examportal.exam.client;

import com.examportal.common.domain.dto.internal.UserLookupRequest;
import com.examportal.common.domain.dto.internal.UserLookupResponse;
import com.examportal.common.domain.dto.internal.UserLookupResponse.UserRef;
import com.examportal.common.domain.dto.internal.UserSearchRequest;
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

    private final WebClient webClient;
    private final String serviceSecret;

    public UserDirectoryClient(WebClient.Builder webClientBuilder,
                               @Value("${services.auth.url}") String authServiceUrl,
                               @Value("${internal.service-secret:}") String serviceSecret) {
        this.webClient = webClientBuilder.baseUrl(authServiceUrl).build();
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

    public Mono<UserRef> findById(UUID userId) {
        return lookup(List.of(), List.of(userId)).flatMap(users -> Mono.justOrEmpty(users.stream().findFirst()));
    }

    public Mono<UserRef> findByEmail(String normalizedEmail) {
        return lookup(List.of(normalizedEmail), List.of())
                .flatMap(users -> Mono.justOrEmpty(users.stream().findFirst()));
    }
}