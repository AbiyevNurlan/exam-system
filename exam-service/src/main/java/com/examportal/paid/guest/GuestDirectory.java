package com.examportal.paid.guest;

import com.examportal.common.security.InternalServiceAuth;
import com.examportal.paid.PaidErrors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * B13b: guests (B13a) live in auth-service's database; exam-service asks GET /internal/guests/{id} (and by code) with
 * the shared service secret: the 7-digit code, the name, the email for receipts, whether blocked. An unknown guest is
 * 404 GUEST_NOT_FOUND; anything else that fails is 503 GUEST_DIRECTORY_UNAVAILABLE (nothing is sold on a guess).
 */
@Component
public class GuestDirectory {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    public record GuestRef(UUID id, int participantCode, String email, String firstName, String lastName, boolean blocked) {
    }

    private final WebClient webClient;
    private final String serviceSecret;

    public GuestDirectory(WebClient.Builder webClientBuilder, @Value("${services.auth.url}") String authServiceUrl,
                          @Value("${internal.service-secret:}") String serviceSecret) {
        this.webClient = webClientBuilder.baseUrl(authServiceUrl).build();
        this.serviceSecret = serviceSecret;
    }

    public Mono<GuestRef> get(UUID guestId) {
        return call("/internal/guests/" + guestId);
    }

    public Mono<GuestRef> byCode(int participantCode) {
        return call("/internal/guests/by-code/" + participantCode);
    }

    /** What a notice email says (item 12); amount in whole minor units, time in UTC */
    public record Notice(String kind, String orderNo, String examTitle, int amountMinor, String currency, java.time.LocalDateTime at) {
    }

    /** Asks auth-service to email the guest a receipt or a refund notice */
    public Mono<Void> notice(UUID guestId, Notice notice) {
        if (!InternalServiceAuth.isConfigured(serviceSecret)) {
            return Mono.error(PaidErrors.guestDirectoryUnavailable());
        }
        return webClient.post().uri("/internal/guests/" + guestId + "/notices")
                .header(InternalServiceAuth.HEADER, serviceSecret)
                .bodyValue(notice)
                .retrieve()
                .toBodilessEntity()
                .timeout(TIMEOUT)
                .then();
    }

    /** The guest when not blocked: 403 GUEST_BLOCKED otherwise */
    public Mono<GuestRef> active(UUID guestId) {
        return get(guestId).flatMap(guest -> guest.blocked() ? Mono.error(PaidErrors.guestBlocked()) : Mono.just(guest));
    }

    private Mono<GuestRef> call(String path) {
        if (!InternalServiceAuth.isConfigured(serviceSecret)) {
            return Mono.error(PaidErrors.guestDirectoryUnavailable());
        }
        return webClient.get().uri(path)
                .header(InternalServiceAuth.HEADER, serviceSecret)
                .retrieve()
                .bodyToMono(GuestRef.class)
                .timeout(TIMEOUT)
                .onErrorMap(error -> error instanceof WebClientResponseException response && response.getStatusCode() == HttpStatus.NOT_FOUND
                        ? PaidErrors.guestNotFound()
                        : PaidErrors.guestDirectoryUnavailable());
    }
}
