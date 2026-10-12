package com.examportal.auth.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * OTP Service
 * Six-digit one-time codes (table email_otp).
 *
 * - The code comes from SecureRandom and is stored only as HMAC-SHA256 (key OTP_HMAC_SECRET);
 *   it is never logged and leaves this class only as the return value of issue().
 * - A new code invalidates the older ones, so at most one code per user and purpose is valid.
 * - Send limits (cooldown, sends per hour) and the wrong-attempt counter are decided in SQL on the
 *   database clock, inside a transaction that first locks the row it decides on, so parallel
 *   requests cannot get past a limit. @Transactional does not work in this service: the boundary
 *   is the TransactionalOperator.
 */
@Slf4j
@Service
public class OtpService {

    public static final String PURPOSE_EMAIL_VERIFICATION = "EMAIL_VERIFICATION";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MIN_KEY_BYTES = 32;
    private static final int CODE_BOUND = 1_000_000;

    private final DatabaseClient databaseClient;
    private final TransactionalOperator transactionalOperator;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${auth.otp.hmac-secret:}")
    private String hmacSecret;

    @Value("${auth.otp.ttl-minutes:10}")
    private int ttlMinutes;

    @Value("${auth.otp.max-attempts:5}")
    private int maxAttempts;

    @Value("${auth.otp.resend-cooldown-seconds:60}")
    private int resendCooldownSeconds;

    @Value("${auth.otp.max-sends-per-hour:5}")
    private int maxSendsPerHour;

    private SecretKeySpec hmacKey;

    public OtpService(DatabaseClient databaseClient, TransactionalOperator transactionalOperator) {
        this.databaseClient = databaseClient;
        this.transactionalOperator = transactionalOperator;
    }

    @PostConstruct
    void validateHmacSecret() {
        if (hmacSecret == null || hmacSecret.isBlank()) {
            throw new IllegalStateException(
                    "OTP_HMAC_SECRET is not set. Provide a base64-encoded secret of at least 32 bytes "
                            + "(e.g. generate one with: openssl rand -base64 32).");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(hmacSecret.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "OTP_HMAC_SECRET is not valid base64. Provide a base64-encoded secret of at least "
                            + "32 bytes (e.g. openssl rand -base64 32).");
        }
        if (keyBytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "OTP_HMAC_SECRET decodes to " + keyBytes.length + " bytes; at least 32 bytes are required.");
        }
        hmacKey = new SecretKeySpec(keyBytes, HMAC_ALGORITHM);
    }

    public int getTtlMinutes() {
        return ttlMinutes;
    }

    /**
     * Create a new code for the user and invalidate the older ones.
     * Emits the plain code (to be emailed, never logged or returned to a client), or completes empty
     * when the cooldown or the hourly limit refuses a new code; then nothing is changed.
     */
    public Mono<String> issue(UUID userId, String purpose) {
        String code = String.format("%06d", secureRandom.nextInt(CODE_BOUND));
        String codeHash = hash(userId, purpose, code);

        // The lock on the user row makes parallel requests for the same user take turns, and each one
        // then counts the codes that the previous one has already committed
        Mono<String> issue = databaseClient.sql("SELECT id FROM users WHERE id = :userId FOR UPDATE")
                .bind("userId", userId)
                .map(row -> row.get(0, UUID.class))
                .one()
                .flatMap(locked -> databaseClient.sql("""
                                WITH recent AS (
                                    SELECT count(*) AS sent, max(created_at) AS last_sent
                                    FROM email_otp
                                    WHERE user_id = :userId AND purpose = :purpose
                                      AND created_at > LOCALTIMESTAMP - make_interval(hours => 1)
                                ), allowed AS (
                                    SELECT 1 FROM recent
                                    WHERE sent < :maxSendsPerHour
                                      AND (last_sent IS NULL
                                           OR last_sent <= LOCALTIMESTAMP - make_interval(secs => :cooldownSeconds))
                                ), invalidated AS (
                                    UPDATE email_otp SET consumed_at = LOCALTIMESTAMP
                                    WHERE user_id = :userId AND purpose = :purpose AND consumed_at IS NULL
                                      AND EXISTS (SELECT 1 FROM allowed)
                                )
                                INSERT INTO email_otp (user_id, code_hash, purpose, expires_at, created_at)
                                SELECT :userId, :codeHash, :purpose,
                                       LOCALTIMESTAMP + make_interval(mins => :ttlMinutes), LOCALTIMESTAMP
                                FROM allowed
                                """)
                        .bind("userId", userId)
                        .bind("purpose", purpose)
                        .bind("codeHash", codeHash)
                        .bind("maxSendsPerHour", maxSendsPerHour)
                        .bind("cooldownSeconds", resendCooldownSeconds)
                        .bind("ttlMinutes", ttlMinutes)
                        .fetch()
                        .rowsUpdated())
                .filter(inserted -> inserted > 0)
                .map(inserted -> code);

        return transactionalOperator.transactional(issue);
    }

    /**
     * Invalidate every code of the user that is still valid, without creating a new one.
     * The rows stay, so the send limits keep counting them. Joins a surrounding transaction.
     */
    public Mono<Void> invalidateAll(UUID userId, String purpose) {
        return databaseClient.sql("""
                        UPDATE email_otp SET consumed_at = LOCALTIMESTAMP
                        WHERE user_id = :userId AND purpose = :purpose AND consumed_at IS NULL
                        """)
                .bind("userId", userId)
                .bind("purpose", purpose)
                .then()
                .as(transactionalOperator::transactional);
    }

    /**
     * Check a code against the user's valid code (not used, not expired, not burned).
     * A match consumes the code (single use). A wrong code is counted, and the code is burned when
     * the counter reaches the limit. Emits false as well when there is no valid code at all.
     * Never raises an error for a wrong code, so the counter is committed; joins a surrounding
     * transaction, which lets the caller make "code consumed" and its own write atomic.
     */
    public Mono<Boolean> verify(UUID userId, String purpose, String code) {
        byte[] presented = hash(userId, purpose, code).getBytes(StandardCharsets.US_ASCII);

        // FOR UPDATE: parallel attempts on the same code take turns and each sees the previous count
        Mono<Boolean> check = databaseClient.sql("""
                        SELECT id, code_hash FROM email_otp
                        WHERE user_id = :userId AND purpose = :purpose
                          AND consumed_at IS NULL AND expires_at > LOCALTIMESTAMP
                        ORDER BY created_at DESC
                        LIMIT 1
                        FOR UPDATE
                        """)
                .bind("userId", userId)
                .bind("purpose", purpose)
                .map(row -> new StoredCode(row.get("id", UUID.class), row.get("code_hash", String.class)))
                .one()
                .flatMap(stored -> {
                    // Constant-time comparison of the two HMAC values
                    boolean matches = MessageDigest.isEqual(presented,
                            stored.codeHash().getBytes(StandardCharsets.US_ASCII));
                    if (matches) {
                        return databaseClient.sql("UPDATE email_otp SET consumed_at = LOCALTIMESTAMP WHERE id = :id")
                                .bind("id", stored.id())
                                .then()
                                .thenReturn(true);
                    }
                    return databaseClient.sql("""
                                    UPDATE email_otp
                                    SET attempts = attempts + 1,
                                        consumed_at = CASE WHEN attempts + 1 >= :maxAttempts THEN LOCALTIMESTAMP END
                                    WHERE id = :id
                                    """)
                            .bind("id", stored.id())
                            .bind("maxAttempts", maxAttempts)
                            .then()
                            .thenReturn(false);
                })
                .defaultIfEmpty(false);

        return transactionalOperator.transactional(check);
    }

    // ==================== Private Helper Methods ====================

    private record StoredCode(UUID id, String codeHash) {
    }

    /**
     * HMAC over purpose, user and code, so a hash is useless for another user or purpose
     */
    private String hash(UUID userId, String purpose, String code) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(hmacKey);
            byte[] digest = mac.doFinal((purpose + ":" + userId + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}