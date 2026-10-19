package com.examportal.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.mockito.Mockito.mock;

/**
 * B13: without Redis a revocation cannot be stored, so it fails (the administrator is not told it worked), while
 * the check lets tokens through instead of locking everybody out.
 */
class TokenRevocationStoreTest {

    @Test
    @SuppressWarnings("unchecked")
    void without_redis_revoking_fails_and_checking_lets_through() {
        TokenRevocationStore store = new TokenRevocationStore(mock(ObjectProvider.class), 900_000L);
        StepVerifier.create(store.revokeAllIssuedBefore(UUID.randomUUID(), Instant.now()))
                .expectError(IllegalStateException.class)
                .verify();
        StepVerifier.create(store.isRevoked(UUID.randomUUID(), new Date()))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_redis_that_does_not_answer_lets_through() {
        ObjectProvider<ReactiveRedisConnectionFactory> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(mock(ReactiveRedisConnectionFactory.class));
        TokenRevocationStore store = new TokenRevocationStore(provider, 900_000L);
        StepVerifier.create(store.isRevoked(UUID.randomUUID(), new Date()))
                .expectNext(false)
                .verifyComplete();
    }
}
