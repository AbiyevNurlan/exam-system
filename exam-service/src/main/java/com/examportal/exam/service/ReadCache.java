package com.examportal.exam.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Read Cache
 * A short-lived Redis cache for read-only screens that are polled or expensive (live monitoring,
 * analytics). Values are stored as JSON under "exam:read:<key>" with a TTL; nothing is ever
 * invalidated, so a value is at most its TTL old.
 *
 * Redis is optional here: without it, when it does not answer within 250 ms or on any error the
 * value is read from the database as if there were no cache, and Redis is left alone for a few
 * seconds so a broken cache does not slow every request down. Only the endpoints that call this
 * class touch it; nothing on a participant's path (start, answer, submit) does.
 *
 * The caller builds the key and must put into it everything the answer depends on, including whose
 * data it is (an INSTRUCTOR must never be served what was cached for someone else).
 */
@Slf4j
@Component
public class ReadCache {

    private static final String PREFIX = "exam:read:";
    private static final Duration REDIS_TIMEOUT = Duration.ofMillis(250);
    private static final long PAUSE_AFTER_FAILURE_MS = 5_000;

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    // Redis is not asked before this moment (epoch millis); set after a failure
    private final AtomicLong pausedUntil = new AtomicLong();

    public ReadCache(ObjectProvider<ReactiveRedisConnectionFactory> connectionFactory, ObjectMapper objectMapper) {
        ReactiveRedisConnectionFactory factory = connectionFactory.getIfAvailable();
        this.redis = factory != null ? new ReactiveStringRedisTemplate(factory) : null;
        this.objectMapper = objectMapper;
    }

    /**
     * The cached value, or the loaded one (which is then cached for ttl)
     */
    public <T> Mono<T> get(String key, Duration ttl, TypeReference<T> type, Supplier<Mono<T>> loader) {
        return get(key, ttl, type, loader, value -> true);
    }

    /**
     * As above, but a loaded value is only cached when storable accepts it (an answer that is
     * incomplete because another service was down should not stay for the whole TTL)
     */
    public <T> Mono<T> get(String key, Duration ttl, TypeReference<T> type, Supplier<Mono<T>> loader,
                           Predicate<T> storable) {
        if (!available()) {
            return Mono.defer(loader);
        }
        return redis.opsForValue().get(PREFIX + key)
                .timeout(REDIS_TIMEOUT)
                .flatMap(json -> decode(json, type))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                // Only a failure of the cache is handled here; an error of the loader is the caller's
                .onErrorResume(e -> {
                    pause(e);
                    return Mono.just(Optional.empty());
                })
                .flatMap(cached -> cached.isPresent()
                        ? Mono.just(cached.get())
                        : Mono.defer(loader).flatMap(value -> store(key, ttl, value, storable).thenReturn(value)));
    }

    // ==================== Private Helper Methods ====================

    private boolean available() {
        return redis != null && System.currentTimeMillis() >= pausedUntil.get();
    }

    private <T> Mono<Void> store(String key, Duration ttl, T value, Predicate<T> storable) {
        if (!available() || !storable.test(value)) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(value))
                .flatMap(json -> redis.opsForValue().set(PREFIX + key, json, ttl))
                .timeout(REDIS_TIMEOUT)
                .then()
                .onErrorResume(e -> {
                    pause(e);
                    return Mono.empty();
                });
    }

    /**
     * A value that cannot be read (written by another version of the service) counts as absent
     */
    private <T> Mono<T> decode(String json, TypeReference<T> type) {
        try {
            return Mono.justOrEmpty(objectMapper.readValue(json, type));
        } catch (Exception e) {
            log.debug("Cached value ignored: {}", e.getClass().getSimpleName());
            return Mono.empty();
        }
    }

    private void pause(Throwable e) {
        pausedUntil.set(System.currentTimeMillis() + PAUSE_AFTER_FAILURE_MS);
        log.warn("Read cache unavailable, reading from the database for {} s: {}",
                PAUSE_AFTER_FAILURE_MS / 1000, e.getClass().getSimpleName());
    }
}
