package com.examportal.auth.repository;

import com.examportal.common.domain.ParticipantCode;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * B13: the administrator's list of teachers (users with the role INSTRUCTOR), newest first on idx_users_instructors.
 * active filters active or deactivated accounts; q matches the start of the first name, last name, "first last" or
 * email, or exactly a 7-digit participant code.
 */
@Repository
@RequiredArgsConstructor
public class InstructorQueryRepository {

    private final DatabaseClient databaseClient;

    public record Row(UUID id, Integer participantCode, String firstName, String lastName, String email, boolean active,
                      boolean mustChangePassword, LocalDateTime temporaryPasswordExpiresAt, LocalDateTime createdAt,
                      LocalDateTime lastLoginAt) {
    }

    public Flux<Row> findPage(Boolean active, String q, int limit, long offset) {
        Map<String, Object> params = new HashMap<>();
        String where = where(active, q, params);
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                SELECT id, participant_code, first_name, last_name, email, active, must_change_password,
                       temp_password_expires_at, created_at, last_login_at
                FROM users""" + where + " ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset")
                .bind("limit", limit)
                .bind("offset", offset);
        for (Map.Entry<String, Object> param : params.entrySet()) {
            spec = spec.bind(param.getKey(), param.getValue());
        }
        return spec.map(row -> new Row(row.get("id", UUID.class), row.get("participant_code", Integer.class),
                        row.get("first_name", String.class), row.get("last_name", String.class), row.get("email", String.class),
                        Boolean.TRUE.equals(row.get("active", Boolean.class)),
                        Boolean.TRUE.equals(row.get("must_change_password", Boolean.class)),
                        row.get("temp_password_expires_at", LocalDateTime.class), row.get("created_at", LocalDateTime.class),
                        row.get("last_login_at", LocalDateTime.class)))
                .all();
    }

    public Mono<Long> count(Boolean active, String q) {
        Map<String, Object> params = new HashMap<>();
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("SELECT COUNT(*) FROM users" + where(active, q, params));
        for (Map.Entry<String, Object> param : params.entrySet()) {
            spec = spec.bind(param.getKey(), param.getValue());
        }
        return spec.map(row -> ((Number) row.get(0)).longValue()).one();
    }

    // Fixed text only; every value is a bind parameter
    private static String where(Boolean active, String q, Map<String, Object> params) {
        StringBuilder where = new StringBuilder(" WHERE role = 'INSTRUCTOR'");
        if (active != null) {
            where.append(" AND active = :active");
            params.put("active", active);
        }
        if (q != null) {
            String term = q.trim().toLowerCase(Locale.ROOT);
            params.put("q", term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
            where.append(" AND (lower(first_name) LIKE :q ESCAPE '!' OR lower(last_name) LIKE :q ESCAPE '!'"
                    + " OR lower(first_name || ' ' || last_name) LIKE :q ESCAPE '!' OR lower(email) LIKE :q ESCAPE '!'");
            Integer code = ParticipantCode.parse(term);
            if (code != null) {
                params.put("code", code);
                where.append(" OR participant_code = :code");
            }
            where.append(")");
        }
        return where.toString();
    }
}
