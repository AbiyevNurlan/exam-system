package com.examportal.auth.repository;

import com.examportal.common.domain.entity.RefreshToken;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Refresh Token Repository
 * Reactive repository for RefreshToken entity operations
 */
@Repository
public interface RefreshTokenRepository extends R2dbcRepository<RefreshToken, UUID> {

    /**
     * Find refresh token by token string
     */
    Mono<RefreshToken> findByToken(String token);

    /**
     * Find valid refresh token by token string
     */
    @Query("SELECT * FROM refresh_tokens WHERE token = :token AND revoked = false AND expires_at > CURRENT_TIMESTAMP")
    Mono<RefreshToken> findValidTokenByToken(String token);

    /**
     * Find all tokens by user ID
     */
    @Query("SELECT * FROM refresh_tokens WHERE user_id = :userId")
    Mono<RefreshToken> findByUserId(UUID userId);

    /**
     * Revoke token
     */
    @Modifying
    @Query("UPDATE refresh_tokens SET revoked = true, updated_at = CURRENT_TIMESTAMP WHERE token = :token")
    Mono<Integer> revokeToken(String token);

    /**
     * Revoke all tokens for user
     */
    @Modifying
    @Query("UPDATE refresh_tokens SET revoked = true, updated_at = CURRENT_TIMESTAMP WHERE user_id = :userId AND revoked = false")
    Mono<Integer> revokeAllUserTokens(UUID userId);

    /**
     * Delete expired tokens
     */
    @Modifying
    @Query("DELETE FROM refresh_tokens WHERE expires_at < :expirationTime")
    Mono<Integer> deleteExpiredTokens(LocalDateTime expirationTime);

    /**
     * Check if token exists and is valid
     */
    @Query("SELECT COUNT(*) > 0 FROM refresh_tokens WHERE token = :token AND revoked = false AND expires_at > CURRENT_TIMESTAMP")
    Mono<Boolean> existsValidToken(String token);
}
