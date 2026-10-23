package com.examportal.guest.security;

import com.examportal.guest.GuestErrors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * B13a item 4: refresh tokens of guests (guest_refresh_tokens, V10). A token is 32 random bytes, handed out once;
 * only its SHA-256 is stored. A sign-in is a family that ends 7 days after the code (family_expires_at, not moved by
 * rotation). Each refresh revokes the token it used and hands out the next one; a revoked token presented again
 * revokes the whole family (it was copied), and the answer is the same 401 as for an unknown token.
 */
@Component
public class GuestRefreshTokens {

    private final DatabaseClient databaseClient;
    private final TransactionalOperator transactionalOperator;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${guest.refresh-token-days:7}")
    private int refreshTokenDays = 7;

    public GuestRefreshTokens(DatabaseClient databaseClient, TransactionalOperator transactionalOperator) {
        this.databaseClient = databaseClient;
        this.transactionalOperator = transactionalOperator;
    }

    /** A rotated token: the guest it belongs to and the next token to hand out */
    public record Rotation(UUID guestId, String refreshToken) {
    }

    private record Row(UUID id, UUID guestId, UUID familyId, LocalDateTime familyExpiresAt, LocalDateTime revokedAt,
                       boolean familyOver) {
    }

    /** A new family for a new sign-in; part of the caller's transaction */
    public Mono<String> issue(UUID guestId) {
        String token = newToken();
        return databaseClient.sql("INSERT INTO guest_refresh_tokens (guest_id, token_hash, family_id, family_expires_at) "
                        + "VALUES (:guestId, :hash, :familyId, LOCALTIMESTAMP + make_interval(days => :days))")
                .bind("guestId", guestId)
                .bind("hash", hash(token))
                .bind("familyId", UUID.randomUUID())
                .bind("days", refreshTokenDays)
                .then()
                .thenReturn(token);
    }

    /**
     * Revokes the token and hands out the next one of its family. 401 REFRESH_TOKEN_INVALID for an unknown, revoked or
     * ended token; a revoked one ends its family first (committed, so the copy stays useless).
     */
    public Mono<Rotation> rotate(String token) {
        if (token == null || token.isBlank()) {
            return Mono.error(GuestErrors.refreshTokenInvalid());
        }
        Mono<Rotation> rotation = databaseClient.sql("SELECT id, guest_id, family_id, family_expires_at, revoked_at, "
                        + "family_expires_at <= LOCALTIMESTAMP AS family_over FROM guest_refresh_tokens WHERE token_hash = :hash FOR UPDATE")
                .bind("hash", hash(token))
                .map(row -> new Row(row.get("id", UUID.class), row.get("guest_id", UUID.class), row.get("family_id", UUID.class),
                        row.get("family_expires_at", LocalDateTime.class), row.get("revoked_at", LocalDateTime.class),
                        Boolean.TRUE.equals(row.get("family_over", Boolean.class))))
                .one()
                .flatMap(row -> {
                    if (row.revokedAt() != null) {
                        // Reuse: the whole family ends; nothing is handed out
                        return revokeFamily(row.familyId()).then(Mono.<Rotation>empty());
                    }
                    if (row.familyOver()) {
                        return Mono.<Rotation>empty();
                    }
                    String next = newToken();
                    return databaseClient.sql("UPDATE guest_refresh_tokens SET revoked_at = LOCALTIMESTAMP WHERE id = :id")
                            .bind("id", row.id())
                            .then()
                            .then(databaseClient.sql("INSERT INTO guest_refresh_tokens (guest_id, token_hash, family_id, family_expires_at) "
                                            + "VALUES (:guestId, :hash, :familyId, :familyExpiresAt)")
                                    .bind("guestId", row.guestId())
                                    .bind("hash", hash(next))
                                    .bind("familyId", row.familyId())
                                    .bind("familyExpiresAt", row.familyExpiresAt())
                                    .then())
                            .thenReturn(new Rotation(row.guestId(), next));
                });
        // The transaction commits first (also the revocation of a copied family), then an empty answer becomes the 401
        return transactionalOperator.transactional(rotation)
                .switchIfEmpty(Mono.error(GuestErrors.refreshTokenInvalid()));
    }

    /** Sign-out: the token stops working; an unknown token is not an error */
    public Mono<Void> revoke(String token) {
        if (token == null || token.isBlank()) {
            return Mono.empty();
        }
        return databaseClient.sql("UPDATE guest_refresh_tokens SET revoked_at = LOCALTIMESTAMP WHERE token_hash = :hash AND revoked_at IS NULL")
                .bind("hash", hash(token))
                .then();
    }

    /** Every sign-in of the guest ends (a block); part of the caller's transaction */
    public Mono<Void> revokeAll(UUID guestId) {
        return databaseClient.sql("UPDATE guest_refresh_tokens SET revoked_at = LOCALTIMESTAMP WHERE guest_id = :guestId AND revoked_at IS NULL")
                .bind("guestId", guestId)
                .then();
    }

    private Mono<Void> revokeFamily(UUID familyId) {
        return databaseClient.sql("UPDATE guest_refresh_tokens SET revoked_at = LOCALTIMESTAMP WHERE family_id = :familyId AND revoked_at IS NULL")
                .bind("familyId", familyId)
                .then();
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 in hex: a token is random, so no key is needed to make its stored form useless */
    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
