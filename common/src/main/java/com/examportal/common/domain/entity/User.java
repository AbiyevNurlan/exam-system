package com.examportal.common.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * User Entity
 * Represents a system user with authentication and authorization information.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Table("users")
public class User extends BaseEntity {

    @Column("email")
    private String email;

    @Column("password_hash")
    private String passwordHash;

    @Column("first_name")
    private String firstName;

    @Column("last_name")
    private String lastName;

    @Column("phone_number")
    private String phoneNumber;

    @Column("role")
    private UserRole role;

    @Column("email_verified")
    private boolean emailVerified;

    @Column("account_locked")
    private boolean accountLocked;

    @Column("failed_login_attempts")
    private int failedLoginAttempts;

    @Column("locked_until")
    private LocalDateTime lockedUntil;

    // The 7-digit participant code, given by the database (default next_participant_code(), auth-service V3).
    // Read only: an insert or an update of the entity never writes it. Display and search only, never security.
    @ReadOnlyProperty
    @Column("participant_code")
    private Integer participantCode;

    // B13 (auth-service V8): a password set by an administrator must be changed before anything else
    @Column("must_change_password")
    private boolean mustChangePassword;

    // B13: until when a temporary password works; null for an own password
    @Column("temp_password_expires_at")
    private LocalDateTime tempPasswordExpiresAt;

    // B13: the last successful sign-in; written by its own statement (UserRepository.touchLastLogin), never by save
    @ReadOnlyProperty
    @Column("last_login_at")
    private LocalDateTime lastLoginAt;

    public String getFullName() {
        return firstName + " " + lastName;
    }
}
