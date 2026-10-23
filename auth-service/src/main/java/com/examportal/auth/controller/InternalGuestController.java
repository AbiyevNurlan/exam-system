package com.examportal.auth.controller;

import com.examportal.common.security.InternalServiceAuth;
import com.examportal.guest.Guest;
import com.examportal.guest.GuestRepository;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * B13b: guest participants (B13a) for exam-service, service to service only: who a guest is (the 7-digit code, the
 * name, the email for receipts) and whether the guest is blocked. Not routed by the gateway; every call carries the
 * shared secret, without it the answer is 404.
 */
@Hidden
@RestController
@RequestMapping("/internal/guests")
@RequiredArgsConstructor
public class InternalGuestController {

    private final GuestRepository guests;
    private final com.examportal.guest.GuestNoticeMailer notices;

    @Value("${internal.service-secret:}")
    private String serviceSecret;

    public record GuestRef(UUID id, int participantCode, String email, String firstName, String lastName, boolean blocked) {

        static GuestRef of(Guest guest) {
            return new GuestRef(guest.id(), guest.participantCode(), guest.email(), guest.firstName(), guest.lastName(), guest.blocked());
        }
    }

    @GetMapping("/{guestId}")
    public Mono<GuestRef> guest(@RequestHeader(name = InternalServiceAuth.HEADER, required = false) String secret,
                                @PathVariable UUID guestId) {
        if (!InternalServiceAuth.matches(secret, serviceSecret)) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
        return guests.findById(guestId).map(GuestRef::of)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @GetMapping("/by-code/{participantCode}")
    public Mono<GuestRef> byCode(@RequestHeader(name = InternalServiceAuth.HEADER, required = false) String secret,
                                 @PathVariable int participantCode) {
        if (!InternalServiceAuth.matches(secret, serviceSecret)) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
        return guests.findByCode(participantCode).map(GuestRef::of)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    /**
     * B13b item 12: the receipt of a paid order or the notice of a refund, by email to the guest (sent detached; 202 at
     * once). exam-service calls it after the order changed.
     */
    @PostMapping("/{guestId}/notices")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> notice(@RequestHeader(name = InternalServiceAuth.HEADER, required = false) String secret,
                             @PathVariable UUID guestId, @RequestBody com.examportal.guest.GuestNoticeMailer.Notice notice) {
        if (!InternalServiceAuth.matches(secret, serviceSecret)) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
        if (notice == null || notice.kind() == null || notice.orderNo() == null || notice.currency() == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST));
        }
        return guests.findById(guestId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .doOnNext(guest -> notices.send(guest, notice))
                .then();
    }
}
