package com.examportal.common.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Password Reset Token Entity
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Table("password_reset_tokens")
public class PasswordResetToken {

    @Id
    private UUID id;

    @Column("user_id")
    private UUID userId;

    @Column("token")
    private String token;

    @Column("expires_at")
    private LocalDateTime expiresAt;

    @Column("used")
    private boolean used;

    @Column("created_at")
    private LocalDateTime createdAt;

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !used && !isExpired();
    }
}
