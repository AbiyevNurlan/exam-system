package com.examportal.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token Revocation Store (B13)
 * Ends the access tokens of one user at once (deactivation, a password reset by an administrator, a password
 * change), not only when they expire. Access tokens are stateless JWTs checked by every service, so the moment is
 * kept in Redis, shared by the gateway and the services: "auth:tokens-valid-after:<userId>" = epoch seconds; a token
 * of that user issued before it (iat) is refused like an invalid one (401). Refresh tokens are revoked in the
 * database as before, so the key only needs to outlive the access tokens (their lifetime plus a minute).
 *
 * A token issued in the same second as the revocation stays valid (iat has second precision), so the new pair that
 * a password change answers with works at once.
 *
 * Without Redis, or when it does not answer within 250 ms, the check lets the token through (and says so in the
 * log, at most once a minute): refusing everybody while Redis is down would stop the whole platform. The refresh
 * tokens are revoked in the database in any case, so a token cannot outlive its 15 minutes.
 */
@Slf4j
@Component
public class TokenRevocationStore {

    static final String PREFIX = "auth:tokens-valid-after:";
    private static final Duration REDIS_TIMEOUT = Duration.ofMillis(250);
    private static final long WARN_EVERY_MS = 60_000;

    private final ReactiveStringRedisTemplate redis;
    private final Duration keyLifetime;
    private final AtomicLong lastWarning = new AtomicLong();

    public TokenRevocationStore(ObjectProvider<ReactiveRedisConnectionFactory> connectionFactory,
                                @Value("${security.jwt.access-token-expiration:900000}") long accessTokenExpirationMs) {
        ReactiveRedisConnectionFactory factory = connectionFactory.getIfAvailable();
        this.redis = factory != null ? new ReactiveStringRedisTemplate(factory) : null;
        this.keyLifetime = Duration.ofMillis(accessTokenExpirationMs).plusMinutes(1);
    }

    /**
     * Every access token of the user issued before now is refused from now on. Fails when Redis cannot store it,
     * so the caller (a deactivation, a reset) does not report success for a revocation that did not happen.
     */
    public Mono<Void> revokeAllIssuedBefore(UUID userId, Instant moment) {
        if (redis == null) {
            return Mono.error(new IllegalStateException("Redis is not configured: access tokens cannot be revoked"));
        }
        return redis.opsForValue().set(PREFIX + userId, String.valueOf(moment.getEpochSecond()), keyLifetime)
                .timeout(Duration.ofSeconds(2))
                .then();
    }

    /**
     * Whether a token of the user issued at issuedAt was revoked; false when nothing was revoked or Redis is not
     * reachable (see the class comment)
     */
    public Mono<Boolean> isRevoked(UUID userId, Date issuedAt) {
        if (redis == null || issuedAt == null) {
            return Mono.just(false);
        }
        long issued = issuedAt.toInstant().getEpochSecond();
        return redis.opsForValue().get(PREFIX + userId)
                .timeout(REDIS_TIMEOUT)
                .map(validAfter -> issued < Long.parseLong(validAfter))
                .defaultIfEmpty(false)
                .onErrorResume(e -> {
                    long now = System.currentTimeMillis();
                    long last = lastWarning.get();
                    if (now - last > WARN_EVERY_MS && lastWarning.compareAndSet(last, now)) {
                        log.warn("Token revocation could not be checked ({}); tokens are accepted until Redis answers",
                                e.getClass().getSimpleName());
                    }
                    return Mono.just(false);
                });
    }
}
