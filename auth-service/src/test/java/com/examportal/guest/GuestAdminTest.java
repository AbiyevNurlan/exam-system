package com.examportal.guest;

import com.examportal.common.exception.ApplicationException;
import com.examportal.guest.security.GuestRefreshTokens;
import com.examportal.guest.web.GuestDtos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B13a item 5 on a real PostgreSQL: the admin's list of guests finds a guest by the 7-digit code, by the start of
 * the email and of a name; names are in Azerbaijani alphabetical order (C before Ç, E before Ə, H before X, I before
 * İ) whatever the case, ties end in the code; sortBy and dir are a whitelist; pages; the card; a block needs a
 * reason and ends the guest's sign-ins; an unknown guest is 404. Skipped without Docker.
 */
class GuestAdminTest {

    private static GuestTestDatabase database;
    private static GuestRepository guests;
    private static GuestAdminService admin;
    private static GuestRefreshTokens refresh;

    @BeforeAll
    static void start() {
        database = GuestTestDatabase.start();
        guests = new GuestRepository(database.db);
        refresh = new GuestRefreshTokens(database.db, database.tx);
        admin = new GuestAdminService(new GuestAdminRepository(database.db), guests, refresh, database.tx);
        for (String[] person : new String[][]{
                {"cavid@x.test", "Cavid", "Əliyev"}, {"camal@x.test", "Çamal", "Eldarov"}, {"elmar@x.test", "elmar", "Həsənov"},
                {"ali@x.test", "Əli", "Xəlilov"}, {"ilqar@x.test", "İlqar", "İsmayılov"}, {"ilkin@x.test", "Ilkin", "Babayev"}}) {
            guests.insert(person[0], LocalDateTime.now(), person[1], person[2], "+994501234567", "v1").block();
        }
    }

    @AfterAll
    static void stop() {
        if (database != null) {
            database.close();
        }
    }

    private static List<String> firstNames(String sortBy, String dir) {
        return admin.page(null, sortBy, dir, 0, 50).block().content().stream().map(GuestDtos.AdminGuestRow::firstName).toList();
    }

    private static String code(Throwable error) {
        return ((ApplicationException) error).getErrorCode();
    }

    @Test
    void namesInAzerbaijaniOrder() {
        assertThat(firstNames("firstName", "asc")).containsExactly("Cavid", "Çamal", "elmar", "Əli", "Ilkin", "İlqar");
        assertThat(firstNames("firstName", "desc")).containsExactly("İlqar", "Ilkin", "Əli", "elmar", "Çamal", "Cavid");
        // Last names: B, E, Ə, H, X, İ
        assertThat(admin.page(null, "lastName", "asc", 0, 50).block().content().stream().map(GuestDtos.AdminGuestRow::lastName))
                .containsExactly("Babayev", "Eldarov", "Əliyev", "Həsənov", "Xəlilov", "İsmayılov");
    }

    @Test
    void searchByCodeEmailAndName() {
        Guest ilqar = guests.findByEmail("ilqar@x.test").block();
        var byCode = admin.page(String.valueOf(ilqar.participantCode()), null, null, 0, 20).block();
        assertThat(byCode.content()).extracting(GuestDtos.AdminGuestRow::email).containsExactly("ilqar@x.test");

        assertThat(admin.page("CAM", null, null, 0, 20).block().content()).extracting(GuestDtos.AdminGuestRow::email)
                .containsExactly("camal@x.test");
        assertThat(admin.page("xəlil", null, null, 0, 20).block().content()).extracting(GuestDtos.AdminGuestRow::email)
                .containsExactly("ali@x.test");
        assertThat(admin.page("100%", null, null, 0, 20).block().totalElements()).isZero();
        assertThatThrownBy(() -> admin.page("a", null, null, 0, 20).block()).satisfies(error -> assertThat(code(error)).isEqualTo("VALIDATION_ERROR"));
    }

    @Test
    void sortAndDirectionAreAWhitelistAndPagesAddUp() {
        assertThatThrownBy(() -> admin.page(null, "password_hash", null, 0, 20).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("VALIDATION_ERROR"));
        assertThatThrownBy(() -> admin.page(null, "code; DROP TABLE users", null, 0, 20).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("VALIDATION_ERROR"));
        assertThatThrownBy(() -> admin.page(null, "code", "sideways", 0, 20).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("VALIDATION_ERROR"));

        var first = admin.page(null, "code", "asc", 0, 4).block();
        var second = admin.page(null, "code", "asc", 1, 4).block();
        assertThat(first.totalElements()).isEqualTo(6);
        assertThat(first.content()).hasSize(4);
        assertThat(second.content()).hasSize(2);
        assertThat(first.content().get(3).participantCode()).isLessThan(second.content().get(0).participantCode());
        // At most 100 rows a page
        assertThat(admin.page(null, null, null, 0, 10_000).block().size()).isEqualTo(100);
    }

    @Test
    void blockNeedsAReasonAndEndsTheSignIns() {
        Guest elmar = guests.findByEmail("elmar@x.test").block();
        String token = refresh.issue(elmar.id()).block();
        UUID adminId = UUID.randomUUID();

        Guest blocked = admin.block(adminId, elmar.id(), new GuestDtos.BlockRequest(true, "  Saxta ödəniş  ")).block();
        assertThat(blocked.blocked()).isTrue();
        assertThat(blocked.blockedReason()).isEqualTo("Saxta ödəniş");
        assertThatThrownBy(() -> refresh.rotate(token).block()).satisfies(error -> assertThat(code(error)).isEqualTo("REFRESH_TOKEN_INVALID"));

        var card = GuestDtos.AdminGuestCard.of(admin.card(elmar.id()).block());
        assertThat(card.blocked()).isTrue();
        assertThat(card.participantCode()).isEqualTo(elmar.participantCode());

        Guest unblocked = admin.block(adminId, elmar.id(), new GuestDtos.BlockRequest(false, "Yoxlanıldı")).block();
        assertThat(unblocked.blocked()).isFalse();

        assertThatThrownBy(() -> admin.card(UUID.randomUUID()).block()).satisfies(error -> assertThat(code(error)).isEqualTo("GUEST_NOT_FOUND"));
        assertThatThrownBy(() -> admin.block(adminId, UUID.randomUUID(), new GuestDtos.BlockRequest(true, "test")).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("GUEST_NOT_FOUND"));
    }
}
