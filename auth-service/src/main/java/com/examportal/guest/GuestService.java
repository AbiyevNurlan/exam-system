package com.examportal.guest;

import com.examportal.guest.security.GuestPrincipal;
import com.examportal.guest.web.GuestDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * B13a item 3: a signed-in guest's own data. Every call reads the guest again, so a block works at once (403
 * GUEST_BLOCKED), not only when the 30 minutes of the access token are over. The email never changes here.
 */
@Service
@RequiredArgsConstructor
public class GuestService {

    private final GuestRepository guests;
    private final GuestAuditLog audit;
    private final TransactionalOperator transactionalOperator;

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
            // Only the names of the fields that changed: never a value
            java.util.List<String> changed = new java.util.ArrayList<>();
            if (!firstName.equals(guest.firstName())) changed.add("firstName");
            if (!lastName.equals(guest.lastName())) changed.add("lastName");
            if (!phone.equals(guest.phone())) changed.add("phone");
            if (changed.isEmpty()) {
                return Mono.just(guest);
            }
            return transactionalOperator.transactional(guests.updateProfile(guest.id(), firstName, lastName, phone)
                    .flatMap(updated -> audit.record(GuestAuditLog.UPDATED, GuestAuditLog.Actor.guest(updated), updated, null,
                            "fields: " + String.join(", ", changed)).thenReturn(updated)));
        }));
    }
}
