package com.examportal.guest;

import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * guest_participants (V9) and the one question guests ask of users: does an account have this email. Every value is
 * bound as a parameter. The participant code is never written here: the trigger of V9 gives it.
 */
@Repository
@RequiredArgsConstructor
public class GuestRepository {

    static final String COLUMNS = "id, email, email_verified_at, first_name, last_name, phone, participant_code, consent_at, "
            + "consent_version, created_at, last_login_at, blocked, blocked_reason";

    private final DatabaseClient databaseClient;

    /** An email as guests and accounts store it: without the spaces around it, in lower case */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * B13a item 2: true when an account (users, any role, active or not) has this email. Such an email never becomes a
     * guest (409 EMAIL_BELONGS_TO_ACCOUNT); the two are never merged. Uses the unique index on lower(email) of V1.
     */
    public Mono<Boolean> emailBelongsToAccount(String email) {
        return databaseClient.sql("SELECT EXISTS (SELECT 1 FROM users WHERE lower(email) = :email)")
                .bind("email", normalizeEmail(email))
                .map(row -> Boolean.TRUE.equals(row.get(0, Boolean.class)))
                .one();
    }

    public Mono<Guest> findByEmail(String email) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM guest_participants WHERE email = :email")
                .bind("email", normalizeEmail(email))
                .map(GuestRepository::guest)
                .one();
    }

    /** B13b: a guest by the 7-digit code (the admin's free entitlement) */
    public Mono<Guest> findByCode(int participantCode) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM guest_participants WHERE participant_code = :code")
                .bind("code", participantCode)
                .map(GuestRepository::guest)
                .one();
    }

    public Mono<Guest> findById(UUID id) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM guest_participants WHERE id = :id")
                .bind("id", id)
                .map(GuestRepository::guest)
                .one();
    }

    /** The new guest with the code the trigger gave; part of the caller's transaction */
    public Mono<Guest> insert(String email, LocalDateTime emailVerifiedAt, String firstName, String lastName, String phone,
                              String consentVersion) {
        return databaseClient.sql("INSERT INTO guest_participants (email, email_verified_at, first_name, last_name, phone, "
                        + "consent_at, consent_version, last_login_at) "
                        + "VALUES (:email, :verifiedAt, :firstName, :lastName, :phone, LOCALTIMESTAMP, :consentVersion, LOCALTIMESTAMP) "
                        + "RETURNING " + COLUMNS)
                .bind("email", normalizeEmail(email))
                .bind("verifiedAt", emailVerifiedAt)
                .bind("firstName", firstName)
                .bind("lastName", lastName)
                .bind("phone", phone)
                .bind("consentVersion", consentVersion)
                .map(GuestRepository::guest)
                .one();
    }

    public Mono<Guest> touchLogin(UUID id) {
        return databaseClient.sql("UPDATE guest_participants SET last_login_at = LOCALTIMESTAMP WHERE id = :id RETURNING " + COLUMNS)
                .bind("id", id)
                .map(GuestRepository::guest)
                .one();
    }

    public Mono<Guest> updateProfile(UUID id, String firstName, String lastName, String phone) {
        return databaseClient.sql("UPDATE guest_participants SET first_name = :firstName, last_name = :lastName, phone = :phone "
                        + "WHERE id = :id RETURNING " + COLUMNS)
                .bind("id", id)
                .bind("firstName", firstName)
                .bind("lastName", lastName)
                .bind("phone", phone)
                .map(GuestRepository::guest)
                .one();
    }

    public Mono<Guest> setBlocked(UUID id, boolean blocked, String reason) {
        return databaseClient.sql("UPDATE guest_participants SET blocked = :blocked, blocked_reason = :reason WHERE id = :id RETURNING " + COLUMNS)
                .bind("id", id)
                .bind("blocked", blocked)
                .bind("reason", reason)
                .map(GuestRepository::guest)
                .one();
    }

    /**
     * Makes the callers for one email take turns until the end of the transaction (the first code of a new guest, the
     * creation of the guest): a row-level lock is not possible before the row exists
     */
    public Mono<Void> lockEmail(String email) {
        return databaseClient.sql("SELECT pg_advisory_xact_lock(hashtextextended('guest-email:' || :email, 0))")
                .bind("email", normalizeEmail(email))
                .map(row -> Boolean.TRUE)
                .one()
                .then();
    }

    static Guest guest(Readable row) {
        return new Guest(
                row.get("id", UUID.class),
                row.get("email", String.class),
                row.get("email_verified_at", LocalDateTime.class),
                row.get("first_name", String.class),
                row.get("last_name", String.class),
                row.get("phone", String.class),
                row.get("participant_code", Integer.class),
                row.get("consent_at", LocalDateTime.class),
                row.get("consent_version", String.class),
                row.get("created_at", LocalDateTime.class),
                row.get("last_login_at", LocalDateTime.class),
                Boolean.TRUE.equals(row.get("blocked", Boolean.class)),
                row.get("blocked_reason", String.class));
    }
}
