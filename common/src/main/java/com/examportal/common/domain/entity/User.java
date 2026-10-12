package com.examportal.common.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
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

    public String getFullName() {
        return firstName + " " + lastName;
    }
}
