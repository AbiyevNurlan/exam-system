package com.examportal.auth.repository;

import com.examportal.common.domain.entity.User;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * User Repository
 * Reactive repository for User entity operations
 */
@Repository
public interface UserRepository extends R2dbcRepository<User, UUID> {

    /**
     * Find user by email
     */
    Mono<User> findByEmail(String email);

    /**
     * Check if user exists by email
     */
    Mono<Boolean> existsByEmail(String email);

    /**
     * Find user by email and active status
     */
    Mono<User> findByEmailAndActive(String email, boolean active);

    /**
     * Increment failed login attempts
     */
    @Modifying
    @Query("UPDATE users SET failed_login_attempts = failed_login_attempts + 1, updated_at = CURRENT_TIMESTAMP WHERE id = :userId")
    Mono<Integer> incrementFailedLoginAttempts(UUID userId);

    /**
     * Reset failed login attempts
     */
    @Modifying
    @Query("UPDATE users SET failed_login_attempts = 0, updated_at = CURRENT_TIMESTAMP WHERE id = :userId")
    Mono<Integer> resetFailedLoginAttempts(UUID userId);

    /**
     * Lock user account
     */
    @Modifying
    @Query("UPDATE users SET account_locked = true, updated_at = CURRENT_TIMESTAMP WHERE id = :userId")
    Mono<Integer> lockAccount(UUID userId);

    /**
     * Unlock user account
     */
    @Modifying
    @Query("UPDATE users SET account_locked = false, failed_login_attempts = 0, updated_at = CURRENT_TIMESTAMP WHERE id = :userId")
    Mono<Integer> unlockAccount(UUID userId);

    /**
     * Update password hash
     */
    @Modifying
    @Query("UPDATE users SET password_hash = :passwordHash, updated_at = CURRENT_TIMESTAMP WHERE id = :userId")
    Mono<Integer> updatePasswordHash(UUID userId, String passwordHash);

    /**
     * Verify email
     */
    @Modifying
    @Query("UPDATE users SET email_verified = true, updated_at = CURRENT_TIMESTAMP WHERE id = :userId")
    Mono<Integer> verifyEmail(UUID userId);
}
