package com.examportal.guest;

import jakarta.annotation.PostConstruct;
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

/**
 * B13a item 3: the one-time codes of the guest sign-in (guest_email_otp, V11), with the rules and the settings of
 * the accounts' codes (OtpService, auth.otp.*): six digits from SecureRandom, 10 minutes, 5 wrong attempts burn a
 * code, 60 seconds between two codes, 5 codes an hour, a new code invalidates the older ones, only the HMAC-SHA256
 * of a code is stored (OTP_HMAC_SECRET). The purpose is GUEST_LOGIN, and the HMAC covers it, so a code of an account
 * is never a guest code. Keyed by email, because a new guest has no row yet: callers for one email take turns on a
 * transaction-level advisory lock. The limits are decided in SQL on the database clock.
 */
@Service
public class GuestOtpService {

    public static final String PURPOSE_GUEST_LOGIN = "GUEST_LOGIN";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int CODE_BOUND = 1_000_000;

    private final DatabaseClient databaseClient;
    private final TransactionalOperator transactionalOperator;
    private final GuestRepository guests;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${auth.otp.hmac-secret:}")
    private String hmacSecret;

    @Value("${auth.otp.ttl-minutes:10}")
    private int ttlMinutes = 10;

    @Value("${auth.otp.max-attempts:5}")
    private int maxAttempts = 5;

    @Value("${auth.otp.resend-cooldown-seconds:60}")
    private int resendCooldownSeconds = 60;

    @Value("${auth.otp.max-sends-per-hour:5}")
    private int maxSendsPerHour = 5;

    private SecretKeySpec hmacKey;

    public GuestOtpService(DatabaseClient databaseClient, TransactionalOperator transactionalOperator, GuestRepository guests) {
        this.databaseClient = databaseClient;
        this.transactionalOperator = transactionalOperator;
        this.guests = guests;
    }

    /** For tests: the secret and the limits without a Spring context */
    GuestOtpService(DatabaseClient databaseClient, TransactionalOperator transactionalOperator, GuestRepository guests,
                    String hmacSecret, int resendCooldownSeconds) {
        this(databaseClient, transactionalOperator, guests);
        this.hmacSecret = hmacSecret;
        this.resendCooldownSeconds = resendCooldownSeconds;
        init();
    }

    @PostConstruct
    void init() {
        // OtpService already refuses a missing, invalid or short OTP_HMAC_SECRET at start
        hmacKey = new SecretKeySpec(Base64.getDecoder().decode(hmacSecret.trim()), HMAC_ALGORITHM);
    }

    public int getTtlMinutes() {
        return ttlMinutes;
    }

    /**
     * A new code for the email, the older ones invalidated. Emits the plain code (to be emailed, never logged or
     * answered), or completes empty when the cooldown or the hourly limit refuses it; then nothing is changed.
     */
    public Mono<String> issue(String email) {
        String normalized = GuestRepository.normalizeEmail(email);
        String code = String.format("%06d", secureRandom.nextInt(CODE_BOUND));
        Mono<String> issue = guests.lockEmail(normalized)
                .then(databaseClient.sql("""
                                WITH recent AS (
                                    SELECT count(*) AS sent, max(created_at) AS last_sent
                                    FROM guest_email_otp
                                    WHERE email = :email AND purpose = :purpose
                                      AND created_at > LOCALTIMESTAMP - make_interval(hours => 1)
                                ), allowed AS (
                                    SELECT 1 FROM recent
                                    WHERE sent < :maxSendsPerHour
                                      AND (last_sent IS NULL
                                           OR last_sent <= LOCALTIMESTAMP - make_interval(secs => :cooldownSeconds))
                                ), invalidated AS (
                                    UPDATE guest_email_otp SET consumed_at = LOCALTIMESTAMP
                                    WHERE email = :email AND purpose = :purpose AND consumed_at IS NULL
                                      AND EXISTS (SELECT 1 FROM allowed)
                                )
                                INSERT INTO guest_email_otp (email, code_hash, purpose, expires_at, created_at)
                                SELECT :email, :codeHash, :purpose,
                                       LOCALTIMESTAMP + make_interval(mins => :ttlMinutes), LOCALTIMESTAMP
                                FROM allowed
                                """)
                        .bind("email", normalized)
                        .bind("purpose", PURPOSE_GUEST_LOGIN)
                        .bind("codeHash", hash(normalized, code))
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
     * Checks a code against the email's valid code. A match consumes it (single use); a wrong code is counted and burns
     * the code at the limit. False also when there is no valid code. Never an error for a wrong code, so the count is
     * committed (its own transaction).
     */
    public Mono<Boolean> verify(String email, String code) {
        String normalized = GuestRepository.normalizeEmail(email);
        if (code == null || !code.matches("[0-9]{6}")) {
            return Mono.just(false);
        }
        byte[] presented = hash(normalized, code).getBytes(StandardCharsets.US_ASCII);
        Mono<Boolean> check = databaseClient.sql("""
                        SELECT id, code_hash FROM guest_email_otp
                        WHERE email = :email AND purpose = :purpose
                          AND consumed_at IS NULL AND expires_at > LOCALTIMESTAMP
                        ORDER BY created_at DESC
                        LIMIT 1
                        FOR UPDATE
                        """)
                .bind("email", normalized)
                .bind("purpose", PURPOSE_GUEST_LOGIN)
                .map(row -> new String[]{row.get("id", java.util.UUID.class).toString(), row.get("code_hash", String.class)})
                .one()
                .flatMap(stored -> {
                    java.util.UUID id = java.util.UUID.fromString(stored[0]);
                    // Constant-time comparison of the two HMAC values
                    if (MessageDigest.isEqual(presented, stored[1].getBytes(StandardCharsets.US_ASCII))) {
                        return databaseClient.sql("UPDATE guest_email_otp SET consumed_at = LOCALTIMESTAMP WHERE id = :id")
                                .bind("id", id).then().thenReturn(true);
                    }
                    return databaseClient.sql("""
                                    UPDATE guest_email_otp
                                    SET attempts = attempts + 1,
                                        consumed_at = CASE WHEN attempts + 1 >= :maxAttempts THEN LOCALTIMESTAMP END
                                    WHERE id = :id
                                    """)
                            .bind("id", id)
                            .bind("maxAttempts", maxAttempts)
                            .then()
                            .thenReturn(false);
                })
                .defaultIfEmpty(false);
        return transactionalOperator.transactional(check);
    }

    /** HMAC over purpose, email and code: useless for another email or purpose, and never an account's code */
    private String hash(String email, String code) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal((PURPOSE_GUEST_LOGIN + ":" + email + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
