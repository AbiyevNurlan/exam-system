package com.examportal.guest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B13a item 2 on a real PostgreSQL: an email of an account, in any case and with spaces around it, is an account's
 * email for the guest sign-in; an email nobody has is not. Skipped without Docker.
 */
class GuestEmailCollisionTest {

    private static GuestTestDatabase database;
    private static GuestRepository guests;

    @BeforeAll
    static void start() {
        database = GuestTestDatabase.start();
        guests = new GuestRepository(database.db);
        database.account("aysel.account@x.test");
    }

    @AfterAll
    static void stop() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void anAccountsEmailIsRecognisedWhateverItsCase() {
        assertThat(guests.emailBelongsToAccount("aysel.account@x.test").block()).isTrue();
        assertThat(guests.emailBelongsToAccount("  Aysel.ACCOUNT@X.test ").block()).isTrue();
        assertThat(guests.emailBelongsToAccount("nobody@x.test").block()).isFalse();
    }

    @Test
    void emailsAreKeptInLowerCase() {
        assertThat(GuestRepository.normalizeEmail("  Leyla.Q@X.Test ")).isEqualTo("leyla.q@x.test");
    }
}
