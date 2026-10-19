package com.examportal.exam.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Action Rate Limiter (B13)
 * A fixed-window counter per key for actions that could be used to probe for accounts, such as adding a group
 * member by participant code: at most limit calls per window. The count is kept in Redis
 * ("exam:limit:<key>", INCR with the window as TTL), so every instance shares it.
 *
 * When Redis is missing, slow (250 ms) or failing, the limit is not lifted: the same count is kept in this
 * instance instead (the worst case is one window per instance). The map is emptied when it grows too large.
 */
@Slf4j
@Component
public class ActionRateLimiter {

    private static final String PREFIX = "exam:limit:";
    private static final Duration REDIS_TIMEOUT = Duration.ofMillis(250);
    private static final int MAX_LOCAL_KEYS = 50_000;

    private final ReactiveStringRedisTemplate redis;
    private final Map<String, long[]> local = new ConcurrentHashMap<>();

    public ActionRateLimiter(ObjectProvider<ReactiveRedisConnectionFactory> connectionFactory) {
        ReactiveRedisConnectionFactory factory = connectionFactory.getIfAvailable();
        this.redis = factory != null ? new ReactiveStringRedisTemplate(factory) : null;
    }

    /**
     * True when the call is within the limit (and counted), false when the limit of the window is reached
     */
    public Mono<Boolean> tryAcquire(String key, int limit, Duration window) {
        if (redis == null) {
            return Mono.just(localAcquire(key, limit, window));
        }
        String redisKey = PREFIX + key;
        return redis.opsForValue().increment(redisKey)
                .flatMap(count -> (count == 1L ? redis.expire(redisKey, window) : Mono.just(true)).thenReturn(count))
                .timeout(REDIS_TIMEOUT)
                .map(count -> count <= limit)
                .onErrorResume(e -> {
                    log.warn("Rate limit counter not reachable in Redis ({}), counting in this instance", e.getClass().getSimpleName());
                    return Mono.just(localAcquire(key, limit, window));
                });
    }

    // [window start millis, count]; synchronized on the entry so one window is counted once
    private boolean localAcquire(String key, int limit, Duration window) {
        if (local.size() > MAX_LOCAL_KEYS) {
            local.clear();
        }
        long now = System.currentTimeMillis();
        long[] entry = local.computeIfAbsent(key, k -> new long[]{now, 0});
        synchronized (entry) {
            if (now - entry[0] >= window.toMillis()) {
                entry[0] = now;
                entry[1] = 0;
            }
            entry[1]++;
            return entry[1] <= limit;
        }
    }
}
