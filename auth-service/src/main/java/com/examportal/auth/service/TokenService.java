package com.examportal.auth.service;

import com.examportal.auth.exception.InvalidTokenException;
import com.examportal.auth.exception.TokenExpiredException;
import com.examportal.auth.repository.RefreshTokenRepository;
import com.examportal.common.domain.entity.RefreshToken;
import com.examportal.common.utils.CodeGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Token Service
 * Manages refresh tokens and their lifecycle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${security.jwt.refresh-token-expiration:604800000}")
    private long refreshTokenExpiration; // 7 days in milliseconds

    /**
     * Create a new refresh token for a user
     */
    public Mono<RefreshToken> createRefreshToken(UUID userId) {
        log.debug("Creating refresh token for user: {}", userId);

        String tokenValue = CodeGenerator.generateSecureToken(64);
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(refreshTokenExpiration / 1000);

        RefreshToken refreshToken = RefreshToken.builder()
                .userId(userId)
                .token(tokenValue)
                .expiresAt(expiresAt)
                .revoked(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        return refreshTokenRepository.save(refreshToken)
                .doOnSuccess(token -> log.info("Refresh token created for user: {}", userId))
                .doOnError(error -> log.error("Failed to create refresh token for user: {}", userId, error));
    }

    /**
     * Validate and retrieve a refresh token
     */
    public Mono<RefreshToken> validateRefreshToken(String token) {
        log.debug("Validating refresh token");

        return refreshTokenRepository.findByToken(token)
                .switchIfEmpty(Mono.error(new InvalidTokenException("Refresh token not found")))
                .flatMap(refreshToken -> {
                    if (refreshToken.isRevoked()) {
                        log.warn("Attempted to use revoked refresh token");
                        return Mono.error(new InvalidTokenException("Refresh token has been revoked"));
                    }
                    if (refreshToken.isExpired()) {
                        log.warn("Attempted to use expired refresh token");
                        return Mono.error(new TokenExpiredException("Refresh token has expired"));
                    }
                    return Mono.just(refreshToken);
                });
    }

    /**
     * Revoke a specific refresh token
     */
    public Mono<Void> revokeRefreshToken(String token) {
        log.debug("Revoking refresh token");

        return refreshTokenRepository.revokeToken(token)
                .then()
                .doOnSuccess(v -> log.info("Refresh token revoked successfully"))
                .doOnError(error -> log.error("Failed to revoke refresh token", error));
    }

    /**
     * Revoke all refresh tokens for a user
     */
    public Mono<Void> revokeAllUserTokens(UUID userId) {
        log.debug("Revoking all tokens for user: {}", userId);

        return refreshTokenRepository.revokeAllUserTokens(userId)
                .then()
                .doOnSuccess(v -> log.info("All tokens revoked for user: {}", userId))
                .doOnError(error -> log.error("Failed to revoke tokens for user: {}", userId, error));
    }

    /**
     * Clean up expired tokens (scheduled task)
     */
    public Mono<Integer> cleanupExpiredTokens() {
        log.debug("Cleaning up expired refresh tokens");

        return refreshTokenRepository.deleteExpiredTokens(LocalDateTime.now())
                .doOnSuccess(count -> log.info("Cleaned up {} expired refresh tokens", count))
                .doOnError(error -> log.error("Failed to cleanup expired tokens", error));
    }

    /**
     * Rotate refresh token (revoke old and create new)
     */
    public Mono<RefreshToken> rotateRefreshToken(String oldToken, UUID userId) {
        log.debug("Rotating refresh token for user: {}", userId);

        return revokeRefreshToken(oldToken)
                .then(createRefreshToken(userId))
                .doOnSuccess(token -> log.info("Refresh token rotated for user: {}", userId));
    }
}
