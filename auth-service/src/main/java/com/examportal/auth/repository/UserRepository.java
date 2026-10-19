package com.examportal.auth.repository;

import com.examportal.common.domain.entity.User;
import com.examportal.common.domain.entity.UserRole;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Collection;
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
     * Active users among the given (normalized) emails; for the internal lookup
     */
    @Query("SELECT * FROM users WHERE email IN (:emails) AND active = true")
    Flux<User> findActiveByEmailIn(Collection<String> emails);

    /**
     * Active users among the given ids; for the internal lookup
     */
    @Query("SELECT * FROM users WHERE id IN (:ids) AND active = true")
    Flux<User> findActiveByIdIn(Collection<UUID> ids);

    /**
     * Active users whose first name, last name, full name or email matches the LIKE pattern (already
     * lower case, with the user's own % and _ escaped by a backslash), or whose id is the given one
     * (null = no id search), or whose participant code is the given one (null = no code search, exact
     * match only); for the internal search.
     */
    @Query("""
            SELECT * FROM users
            WHERE active = true
              AND (lower(first_name) LIKE :pattern ESCAPE '\\'
                   OR lower(last_name) LIKE :pattern ESCAPE '\\'
                   OR lower(first_name || ' ' || last_name) LIKE :pattern ESCAPE '\\'
                   OR lower(email) LIKE :pattern ESCAPE '\\'
                   OR id = :id
                   OR participant_code = :code)
            ORDER BY btrim(last_name) COLLATE az_ci, btrim(first_name) COLLATE az_ci, participant_code
            LIMIT :limit
            """)
    Flux<User> searchActive(@Param("pattern") String pattern, @Param("id") UUID id, @Param("code") Integer code,
                            @Param("limit") int limit);

    /**
     * Check if any user has the given role
     */
    Mono<Boolean> existsByRole(UserRole role);

    /**
     * Create the first ADMIN, only if no ADMIN exists and the email is free; returns 1 if it was created
     */
    @Modifying
    @Query("""
            INSERT INTO users (email, password_hash, first_name, last_name, role, email_verified)
            SELECT :email, :passwordHash, 'System', 'Administrator', 'ADMIN', true
            WHERE NOT EXISTS (SELECT 1 FROM users WHERE role = 'ADMIN')
            ON CONFLICT DO NOTHING
            """)
    Mono<Integer> insertFirstAdmin(@Param("email") String email, @Param("passwordHash") String passwordHash);

    /**
     * Count a failed login and lock the account once the limit is reached, in ONE atomic statement
     * so concurrent wrong attempts cannot skip the limit. Uses the database clock.
     * lockMinutes = 0 locks without an expiry (only an admin can unlock).
     * Emits whether the account is now locked; empty if the email is unknown or already locked.
     * An unverified account is never counted or locked: anyone can register someone else's email,
     * and must not be able to lock its owner out before the address is verified.
     * The build has no -parameters flag, so parameters that are reused in the SQL need explicit @Param names.
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
            WHERE email = :email AND account_locked = false AND email_verified = true
            RETURNING account_locked
            """)
    Mono<Boolean> registerFailedLogin(@Param("email") String email,
                                      @Param("maxAttempts") int maxAttempts,
                                      @Param("lockMinutes") int lockMinutes);

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
    Mono<Integer> unlockIfLockExpired(@Param("userId") UUID userId);

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
    // The owner sets an own password (change, reset with a code): a temporary password set by an administrator ends (B13)
    @Query("UPDATE users SET password_hash = :passwordHash, must_change_password = false, temp_password_expires_at = NULL, "
            + "updated_at = CURRENT_TIMESTAMP WHERE id = :userId")
    Mono<Integer> updatePasswordHash(UUID userId, String passwordHash);

    /**
     * The user's own profile fields (PUT /auth/profile); an inactive account is not changed
     */
    @Modifying
    @Query("""
            UPDATE users SET first_name = :firstName, last_name = :lastName, phone_number = :phoneNumber,
                             updated_at = CURRENT_TIMESTAMP
            WHERE id = :userId AND active = true
            """)
    Mono<Integer> updateProfile(@Param("userId") UUID userId,
                                @Param("firstName") String firstName,
                                @Param("lastName") String lastName,
                                @Param("phoneNumber") String phoneNumber);

    /**
     * Find user by id and lock the row until the surrounding transaction ends, so two password
     * changes (or a change and a reset) of the same account take turns
     */
    @Query("SELECT * FROM users WHERE id = :userId FOR UPDATE")
    Mono<User> findByIdForUpdate(@Param("userId") UUID userId);

    /**
     * Find user by email and lock the row until the surrounding transaction ends
     */
    @Query("SELECT * FROM users WHERE email = :email FOR UPDATE")
    Mono<User> findByEmailForUpdate(@Param("email") String email);

    /**
     * Hand an unverified account over to a new registration: its password and name are replaced and
     * it starts again as a fresh, active, unlocked account. Never touches a verified account.
     */
    @Modifying
    @Query("""
            UPDATE users
            SET password_hash = :passwordHash, first_name = :firstName, last_name = :lastName,
                phone_number = :phoneNumber, active = true,
                failed_login_attempts = 0, account_locked = false, locked_until = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :userId AND email_verified = false
            """)
    Mono<Integer> replaceUnverifiedAccount(@Param("userId") UUID userId,
                                           @Param("passwordHash") String passwordHash,
                                           @Param("firstName") String firstName,
                                           @Param("lastName") String lastName,
                                           @Param("phoneNumber") String phoneNumber);

    /**
     * Verify email
     */
    @Modifying
    @Query("UPDATE users SET email_verified = true, updated_at = CURRENT_TIMESTAMP WHERE id = :userId")
    Mono<Integer> verifyEmail(UUID userId);

    // ==================== B13: teachers made by an administrator ====================

    /**
     * The moment of a successful sign-in, for the administrator's list of teachers
     */
    @Modifying
    @Query("UPDATE users SET last_login_at = CURRENT_TIMESTAMP WHERE id = :userId")
    Mono<Integer> touchLastLogin(UUID userId);

    /**
     * A temporary password set by an administrator for a teacher: the owner must change it before anything else
     * and it works until expiresAt. Only an INSTRUCTOR account is changed.
     */
    @Modifying
    @Query("""
            UPDATE users SET password_hash = :passwordHash, must_change_password = true, temp_password_expires_at = :expiresAt,
                             updated_at = CURRENT_TIMESTAMP
            WHERE id = :userId AND role = 'INSTRUCTOR'
            """)
    Mono<Integer> setTemporaryPassword(@Param("userId") UUID userId, @Param("passwordHash") String passwordHash,
                                       @Param("expiresAt") LocalDateTime expiresAt);

    /**
     * Name and active state of a teacher; only an INSTRUCTOR account is changed
     */
    @Modifying
    @Query("""
            UPDATE users SET first_name = :firstName, last_name = :lastName, active = :active, updated_at = CURRENT_TIMESTAMP
            WHERE id = :userId AND role = 'INSTRUCTOR'
            """)
    Mono<Integer> updateInstructor(@Param("userId") UUID userId, @Param("firstName") String firstName,
                                   @Param("lastName") String lastName, @Param("active") boolean active);

    /**
     * One teacher, locked for the change in the caller's transaction
     */
    @Query("SELECT * FROM users WHERE id = :userId AND role = 'INSTRUCTOR' FOR UPDATE")
    Mono<User> findInstructorForUpdate(@Param("userId") UUID userId);

    @Query("SELECT * FROM users WHERE id = :userId AND role = 'INSTRUCTOR'")
    Mono<User> findInstructor(@Param("userId") UUID userId);
}
