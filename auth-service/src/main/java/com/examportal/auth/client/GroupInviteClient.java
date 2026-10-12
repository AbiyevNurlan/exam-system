package com.examportal.auth.client;

import com.examportal.common.domain.dto.internal.ApplyGroupInvitesRequest;
import com.examportal.common.domain.dto.internal.ApplyGroupInvitesResponse;
import com.examportal.common.domain.entity.User;
import com.examportal.common.security.InternalServiceAuth;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Group Invite Client
 * The hook that turns pending group invites (emails imported by CSV in exam-service before an
 * account existed) into memberships. Groups live in exam-service's database, so this calls its
 * internal endpoint, authenticated with the shared service secret.
 *
 * Call applyForVerifiedUser whenever a user is known to own a VERIFIED email: today on login; the
 * future email verification (OTP) flow MUST call it right after it sets email_verified = true.
 * It never fails the caller: a problem is logged and the invites stay pending for the next call.
 */
@Slf4j
@Component
public class GroupInviteClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final WebClient webClient;
    private final String serviceSecret;

    public GroupInviteClient(WebClient.Builder webClientBuilder,
                             @Value("${services.exam.url}") String examServiceUrl,
                             @Value("${internal.service-secret:}") String serviceSecret) {
        this.webClient = webClientBuilder.baseUrl(examServiceUrl).build();
        this.serviceSecret = serviceSecret;
    }

    /**
     * Does nothing for a user whose email is not verified: an unverified account must not gain
     * group access through an address it has not proven to own.
     */
    public Mono<Void> applyForVerifiedUser(User user) {
        if (!user.isEmailVerified() || !InternalServiceAuth.isConfigured(serviceSecret)) {
            return Mono.empty();
        }
        return webClient.post()
                .uri("/internal/group-invites/apply")
                .header(InternalServiceAuth.HEADER, serviceSecret)
                .bodyValue(ApplyGroupInvitesRequest.builder().userId(user.getId()).email(user.getEmail()).build())
                .retrieve()
                .bodyToMono(ApplyGroupInvitesResponse.class)
                .timeout(TIMEOUT)
                .then()
                .onErrorResume(e -> {
                    // Only the exception type: the message may echo the request
                    log.warn("Pending group invites could not be applied for user {}: {}",
                            user.getId(), e.getClass().getSimpleName());
                    return Mono.empty();
                });
    }
}