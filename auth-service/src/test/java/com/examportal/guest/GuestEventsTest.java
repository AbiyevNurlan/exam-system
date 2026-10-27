package com.examportal.guest;

import com.examportal.auth.controller.InternalGuestController;
import com.examportal.common.exception.ApplicationException;
import com.examportal.guest.security.GuestRefreshTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * B13b item 17 on a real PostgreSQL: exam-service's order events reach the guest's audit only with the shared secret
 * (404 without it) and a clean action name (400 otherwise), as SYSTEM or GUEST events with the guest's code; the
 * administrator reads a guest's events newest first, paged, emails masked; an unknown guest is 404. Skipped without
 * Docker.
 */
class GuestEventsTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-internal";
    private static GuestTestDatabase database;
    private static GuestRepository guests;
    private static InternalGuestController internal;
    private static GuestAdminService admin;

    @BeforeAll
    static void start() {
        database = GuestTestDatabase.start();
        guests = new GuestRepository(database.db);
        GuestAuditLog audit = new GuestAuditLog(database.db);
        internal = new InternalGuestController(guests, mock(GuestNoticeMailer.class), audit);
        ReflectionTestUtils.setField(internal, "serviceSecret", SECRET);
        admin = new GuestAdminService(new GuestAdminRepository(database.db), guests, new GuestRefreshTokens(database.db, database.tx), database.tx, audit);
    }

    @AfterAll
    static void stop() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void orderEventsReachTheGuestsAuditAndTheAdministratorReadsThem() {
        Guest guest = guests.insert("events.guest@x.test", LocalDateTime.now(), "Aysel", "Quliyeva", "+994501234567", "v1").block();
        internal.event(SECRET, guest.id(), new InternalGuestController.EventRequest("ORDER_CREATED", "GUEST", null, null,
                "order YK-1, exam 7, 2500 AZN")).block();
        internal.event(SECRET, guest.id(), new InternalGuestController.EventRequest("ORDER_PAID", "SYSTEM", null, null,
                "order YK-1, exam 7, 2500 AZN")).block();

        assertThatThrownBy(() -> internal.event("wrong", guest.id(), new InternalGuestController.EventRequest("ORDER_PAID", "SYSTEM", null, null, null)).block())
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> internal.event(SECRET, guest.id(), new InternalGuestController.EventRequest("order paid; drop", "SYSTEM", null, null, null)).block())
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        assertThatThrownBy(() -> internal.event(SECRET, UUID.randomUUID(), new InternalGuestController.EventRequest("ORDER_PAID", "SYSTEM", null, null, null)).block())
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");

        var page = admin.events(guest.id(), 0, 1).block();
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("GUEST_ORDER_PAID");
            assertThat(event.actorKind()).isEqualTo("SYSTEM");
            assertThat(event.targetCode()).isEqualTo(guest.participantCode());
            assertThat(event.emailMasked()).isEqualTo("e***@x.test");
        });
        var second = admin.events(guest.id(), 1, 1).block().content().get(0);
        assertThat(second.action()).isEqualTo("GUEST_ORDER_CREATED");
        assertThat(second.actorKind()).isEqualTo("GUEST");
        assertThat(second.actorCode()).isEqualTo(guest.participantCode());

        assertThatThrownBy(() -> admin.events(UUID.randomUUID(), 0, 20).block())
                .satisfies(error -> assertThat(((ApplicationException) error).getErrorCode()).isEqualTo("GUEST_NOT_FOUND"));
    }
}
