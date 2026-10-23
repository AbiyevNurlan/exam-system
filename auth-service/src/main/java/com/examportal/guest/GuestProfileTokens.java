package com.examportal.guest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * B13a item 3: the profile token (guest_profile_tokens, V11), the proof that an email without a guest was verified
 * with a code just now. 32 random bytes, valid 15 minutes, stored only as its SHA-256. It creates one guest: once
 * used it remembers the guest (guest_id), and the same token again answers for that guest without changing it.
 */
@Component
public class GuestProfileTokens {

    private final DatabaseClient databaseClient;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${guest.profile-token-minutes:15}")
    private int profileTokenMinutes = 15;

    public GuestProfileTokens(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /** A profile token as it is in the table, locked for the rest of the transaction */
    public record Stored(String email, LocalDateTime verifiedAt, UUID guestId, boolean valid) {
    }

    public Duration lifetime() {
        return Duration.ofMinutes(profileTokenMinutes);
    }

    public Mono<String> create(String email) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return databaseClient.sql("INSERT INTO guest_profile_tokens (token_hash, email, verified_at, expires_at) "
                        + "VALUES (:hash, :email, LOCALTIMESTAMP, LOCALTIMESTAMP + make_interval(mins => :minutes))")
                .bind("hash", hash(token))
                .bind("email", GuestRepository.normalizeEmail(email))
                .bind("minutes", profileTokenMinutes)
                .then()
                .thenReturn(token);
    }

    /** The token with a row lock; empty for an unknown token. valid is false once its 15 minutes are over. */
    public Mono<Stored> lock(String token) {
        if (token == null || token.isBlank()) {
            return Mono.empty();
        }
        return databaseClient.sql("SELECT email, verified_at, guest_id, expires_at > LOCALTIMESTAMP AS valid "
                        + "FROM guest_profile_tokens WHERE token_hash = :hash FOR UPDATE")
                .bind("hash", hash(token))
                .map(row -> new Stored(row.get("email", String.class), row.get("verified_at", LocalDateTime.class),
                        row.get("guest_id", UUID.class), Boolean.TRUE.equals(row.get("valid", Boolean.class))))
                .one();
    }

    public Mono<Void> markUsed(String token, UUID guestId) {
        return databaseClient.sql("UPDATE guest_profile_tokens SET guest_id = :guestId, used_at = LOCALTIMESTAMP WHERE token_hash = :hash")
                .bind("guestId", guestId)
                .bind("hash", hash(token))
                .then();
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
