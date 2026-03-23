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
 * Refresh Token Entity
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Table("refresh_tokens")
public class RefreshToken {

    @Id
    private UUID id;

    @Column("user_id")
    private UUID userId;

    @Column("token")
    private String token;

    @Column("expires_at")
    private LocalDateTime expiresAt;

    @Column("revoked")
    private boolean revoked;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !revoked && !isExpired();
    }
}
