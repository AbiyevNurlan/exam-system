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
     * Count a failed login and lock the account once the limit is reached, in ONE atomic statement
     * so concurrent wrong attempts cannot skip the limit. Uses the database clock.
     * lockMinutes = 0 locks without an expiry (only an admin can unlock).
     * Emits whether the account is now locked; empty if the email is unknown or already locked.
     */
    @Query("""
            UPDATE users
            SET failed_login_attempts = failed_login_attempts + 1,
                account_locked = (failed_login_attempts + 1 >= :maxAttempts),
                locked_until = CASE
                    WHEN failed_login_attempts + 1 >= :maxAttempts AND :lockMinutes > 0
                        THEN LOCALTIMESTAMP + make_interval(mins => :lockMinutes)
                    WHEN failed_login_attempts + 1 >= :maxAttempts THEN NULL
                    ELSE locked_until END,
                updated_at = CURRENT_TIMESTAMP
            WHERE email = :email AND account_locked = false
            RETURNING account_locked
            """)
    Mono<Boolean> registerFailedLogin(String email, int maxAttempts, int lockMinutes);

    /**
     * Lift a temporary lock whose locked_until has passed (database clock); returns 1 if it was lifted
     */
    @Modifying
    @Query("""
            UPDATE users
            SET account_locked = false, locked_until = NULL, failed_login_attempts = 0, updated_at = CURRENT_TIMESTAMP
            WHERE id = :userId AND account_locked = true
              AND locked_until IS NOT NULL AND locked_until <= LOCALTIMESTAMP
            """)
    Mono<Integer> unlockIfLockExpired(UUID userId);

    /**
     * Reset failed login attempts
     */
    @Modifying
    @Query("UPDATE users SET failed_login_attempts = 0, updated_at = CURRENT_TIMESTAMP WHERE id = :userId")
    Mono<Integer> resetFailedLoginAttempts(UUID userId);

    /**
     * Unlock user account
     */
    @Modifying
    @Query("UPDATE users SET account_locked = false, locked_until = NULL, failed_login_attempts = 0, updated_at = CURRENT_TIMESTAMP WHERE id = :userId")
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
