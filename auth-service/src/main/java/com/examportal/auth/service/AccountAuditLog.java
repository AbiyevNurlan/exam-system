package com.examportal.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Audit rows for changes a user makes to the own account, in the table of the account audit
 * (admin_audit_log, read by GET /api/admin/audit as source AUTH_SERVICE): actor and target are the
 * same user. Never a password, a hash or a field value, only what happened.
 */
@Component
@RequiredArgsConstructor
public class AccountAuditLog {

    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String PROFILE_UPDATED = "PROFILE_UPDATED";

    private final DatabaseClient databaseClient;

    /**
     * One row; part of the caller's transaction
     */
    public Mono<Void> record(UUID userId, String action) {
        return databaseClient.sql("INSERT INTO admin_audit_log (admin_user_id, action, target_user_id) "
                        + "VALUES (:userId, :action, :userId)")
                .bind("userId", userId)
                .bind("action", action)
                .then();
    }
}
