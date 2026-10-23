package com.examportal.guest;

import com.examportal.guest.security.GuestPrincipal;
import com.examportal.guest.web.GuestDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * B13a item 3: a signed-in guest's own data. Every call reads the guest again, so a block works at once (403
 * GUEST_BLOCKED), not only when the 30 minutes of the access token are over. The email never changes here.
 */
@Service
@RequiredArgsConstructor
public class GuestService {

    private final GuestRepository guests;

    public Mono<Guest> current(GuestPrincipal principal) {
        return guests.findById(principal.id())
                .switchIfEmpty(Mono.error(GuestErrors.notFound()))
                .flatMap(guest -> guest.blocked() ? Mono.error(GuestErrors.blocked()) : Mono.just(guest));
    }

    public Mono<Guest> update(GuestPrincipal principal, GuestDtos.UpdateMeRequest request) {
        return current(principal).flatMap(guest -> Mono.defer(() -> {
            String firstName = request.firstName() == null ? guest.firstName() : GuestAuthService.name("firstName", request.firstName());
            String lastName = request.lastName() == null ? guest.lastName() : GuestAuthService.name("lastName", request.lastName());
            String phone = request.phone() == null ? guest.phone() : GuestAuthService.phone(request.phone());
            return guests.updateProfile(guest.id(), firstName, lastName, phone);
        }));
    }
}
