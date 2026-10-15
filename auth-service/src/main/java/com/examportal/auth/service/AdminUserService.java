package com.examportal.auth.service;

import com.examportal.auth.repository.UserRepository;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.auth.AdminUserResponse;
import com.examportal.common.domain.entity.User;
import com.examportal.common.domain.entity.UserRole;
import com.examportal.common.exception.ApplicationException;
import com.examportal.common.exception.ResourceNotFoundException;
import com.examportal.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Admin User Service
 * User search and account unlock for administrators, with an audit trail.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    static final int DEFAULT_PAGE_SIZE = 25;
    static final int MAX_PAGE_SIZE = 50;
    static final int MIN_NAME_LENGTH = 3;

    private static final String ACTION_UNLOCK_USER = "UNLOCK_USER";
    private static final String ACTION_VERIFY_EMAIL = "VERIFY_EMAIL";

    // A temporary lock that has run out is only cleared on the next login, so "locked" means still in force
    private static final String LOCKED_NOW =
            "(account_locked = true AND (locked_until IS NULL OR locked_until > LOCALTIMESTAMP))";

    private static final String SELECT_USERS = "SELECT id, email, participant_code, first_name, last_name, role, "
            + LOCKED_NOW + " AS locked, "
            + "CASE WHEN " + LOCKED_NOW + " THEN locked_until END AS locked_until, "
            + "failed_login_attempts, email_verified, created_at FROM users";

    private final DatabaseClient databaseClient;
    private final UserRepository userRepository;
    private final TransactionalOperator transactionalOperator;

    /**
     * Search users. All filters are optional and combined with AND; every value is bound as a parameter.
     */
    public Mono<PageResponse<AdminUserResponse>> searchUsers(String email, String id, String name,
                                                             String locked, String page, String size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();

        if (StringUtils.hasText(email)) {
            where.append(" AND lower(email) = :email");
            params.put("email", email.trim().toLowerCase(Locale.ROOT));
        }
        if (StringUtils.hasText(id)) {
            where.append(" AND id = :id");
            params.put("id", parseUuid(id));
        }
        if (StringUtils.hasText(name)) {
            String prefix = name.trim().toLowerCase(Locale.ROOT);
            if (prefix.length() < MIN_NAME_LENGTH) {
                throw new ValidationException("name", "must be at least " + MIN_NAME_LENGTH + " characters");
            }
            where.append(" AND (lower(first_name) LIKE :name ESCAPE '!' OR lower(last_name) LIKE :name ESCAPE '!')");
            params.put("name", escapeLike(prefix) + "%");
        }
        if (StringUtils.hasText(locked)) {
            where.append(parseBoolean("locked", locked) ? " AND " : " AND NOT ").append(LOCKED_NOW);
        }

        int pageNumber = Math.max(parseInt("page", page, 0), 0);
        int pageSize = Math.min(Math.max(parseInt("size", size, DEFAULT_PAGE_SIZE), 1), MAX_PAGE_SIZE);

        DatabaseClient.GenericExecuteSpec countQuery = bindAll(
                databaseClient.sql("SELECT count(*) FROM users" + where), params);

        DatabaseClient.GenericExecuteSpec pageQuery = bindAll(
                databaseClient.sql(SELECT_USERS + where + " ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset"),
                params)
                .bind("limit", pageSize)
                .bind("offset", (long) pageNumber * pageSize);

        return countQuery.map(row -> row.get(0, Long.class)).one()
                .flatMap(total -> pageQuery
                        .map(row -> AdminUserResponse.builder()
                                .id(row.get("id", UUID.class))
                                .email(row.get("email", String.class))
                                .participantCode(row.get("participant_code", Integer.class))
                                .firstName(row.get("first_name", String.class))
                                .lastName(row.get("last_name", String.class))
                                .role(row.get("role", String.class))
                                .locked(Boolean.TRUE.equals(row.get("locked", Boolean.class)))
                                .lockedUntil(row.get("locked_until", LocalDateTime.class))
                                .failedAttempts(intOrZero(row.get("failed_login_attempts", Integer.class)))
                                .emailVerified(Boolean.TRUE.equals(row.get("email_verified", Boolean.class)))
                                .createdAt(row.get("created_at", LocalDateTime.class))
                                .build())
                        .all()
                        .collectList()
                        .map(users -> PageResponse.of(users, pageNumber, pageSize, total)));
    }

    /**
     * Unlock an account and reset its failed-login counter. Idempotent; email verification is not touched.
     * The unlock and its audit row are written in one transaction.
     */
    public Mono<Void> unlockUser(String id, String adminEmail) {
        UUID userId = parseUuid(id);

        return findActiveAdmin(adminEmail)
                .flatMap(admin -> userRepository.unlockAccount(userId)
                        .flatMap(updated -> {
                            if (updated == 0) {
                                return Mono.error(new ResourceNotFoundException("User", userId.toString()));
                            }
                            return writeAuditLog(admin.getId(), ACTION_UNLOCK_USER, userId)
                                    .doOnSuccess(v -> log.info("Admin {} unlocked user {}", admin.getId(), userId));
                        }))
                .then()
                // @Transactional is not applied at runtime in this service, so the TransactionalOperator is the real boundary
                .as(transactionalOperator::transactional);
    }

    /**
     * Mark a user's email address as verified without a code (for accounts created before email
     * verification existed, and for support). Idempotent; lock state and password are not touched.
     * The change and its audit row are written in one transaction.
     */
    public Mono<Void> verifyUserEmail(String id, String adminEmail) {
        UUID userId = parseUuid(id);

        return findActiveAdmin(adminEmail)
                .flatMap(admin -> userRepository.verifyEmail(userId)
                        .flatMap(updated -> {
                            if (updated == 0) {
                                return Mono.error(new ResourceNotFoundException("User", userId.toString()));
                            }
                            return writeAuditLog(admin.getId(), ACTION_VERIFY_EMAIL, userId)
                                    .doOnSuccess(v -> log.info("Admin {} verified the email of user {}", admin.getId(), userId));
                        }))
                .then()
                .as(transactionalOperator::transactional);
    }

    // ==================== Private Helper Methods ====================

    /**
     * Resolve the calling admin from the token email; the token may be older than a role change
     */
    private Mono<User> findActiveAdmin(String adminEmail) {
        return userRepository.findByEmail(adminEmail)
                .filter(user -> user.isActive() && user.getRole() == UserRole.ADMIN)
                .switchIfEmpty(Mono.error(new ApplicationException(
                        "Access denied", HttpStatus.FORBIDDEN, "ACCESS_DENIED")));
    }

    private Mono<Void> writeAuditLog(UUID adminUserId, String action, UUID targetUserId) {
        return databaseClient.sql("INSERT INTO admin_audit_log (admin_user_id, action, target_user_id) "
                        + "VALUES (:adminUserId, :action, :targetUserId)")
                .bind("adminUserId", adminUserId)
                .bind("action", action)
                .bind("targetUserId", targetUserId)
                .then();
    }

    private DatabaseClient.GenericExecuteSpec bindAll(DatabaseClient.GenericExecuteSpec spec, Map<String, Object> params) {
        for (Map.Entry<String, Object> param : params.entrySet()) {
            spec = spec.bind(param.getKey(), param.getValue());
        }
        return spec;
    }

    /**
     * Escape LIKE wildcards so the search text only ever matches literally (escape character is '!')
     */
    private String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private int intOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("id", "must be a valid UUID");
        }
    }

    private boolean parseBoolean(String field, String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("true") && !normalized.equals("false")) {
            throw new ValidationException(field, "must be true or false");
        }
        return Boolean.parseBoolean(normalized);
    }

    private int parseInt(String field, String value, int defaultValue) {
        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new ValidationException(field, "must be a number");
        }
    }
}
