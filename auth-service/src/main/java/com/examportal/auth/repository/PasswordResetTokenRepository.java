package com.examportal.auth.repository;

import com.examportal.common.domain.entity.PasswordResetToken;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Password Reset Token Repository
 * Reactive repository for PasswordResetToken entity operations
 */
@Repository
public interface PasswordResetTokenRepository extends R2dbcRepository<PasswordResetToken, UUID> {

    /**
     * Find password reset token by token string
     */
    Mono<PasswordResetToken> findByToken(String token);

    /**
     * Find valid password reset token
     */
    @Query("SELECT * FROM password_reset_tokens WHERE token = :token AND used = false AND expires_at > CURRENT_TIMESTAMP")
    Mono<PasswordResetToken> findValidTokenByToken(String token);

    /**
     * Find by user ID
     */
    Mono<PasswordResetToken> findByUserId(UUID userId);

    /**
     * Mark token as used
     */
    @Modifying
    @Query("UPDATE password_reset_tokens SET used = true WHERE token = :token")
    Mono<Integer> markTokenAsUsed(String token);

    /**
     * Delete expired tokens
     */
    @Modifying
    @Query("DELETE FROM password_reset_tokens WHERE expires_at < :expirationTime")
    Mono<Integer> deleteExpiredTokens(LocalDateTime expirationTime);

    /**
     * Delete all tokens for user
     */
    @Modifying
    @Query("DELETE FROM password_reset_tokens WHERE user_id = :userId")
    Mono<Integer> deleteAllByUserId(UUID userId);

    /**
     * Check if valid token exists
     */
    @Query("SELECT COUNT(*) > 0 FROM password_reset_tokens WHERE token = :token AND used = false AND expires_at > CURRENT_TIMESTAMP")
    Mono<Boolean> existsValidToken(String token);
}
