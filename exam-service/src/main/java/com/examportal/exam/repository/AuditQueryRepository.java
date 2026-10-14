package com.examportal.exam.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * Audit Query Repository
 * Reads group_audit_log for the audit list: the count of the matching rows and the newest of them,
 * two statements, newest first (idx_group_audit_created, V11).
 */
@Repository
@RequiredArgsConstructor
public class AuditQueryRepository {

    private final DatabaseClient databaseClient;

    /**
     * Every field is optional. from inclusive, to exclusive (UTC). q: part of the action or the details.
     */
    public record Filter(String action, UUID actorId, LocalDateTime from, LocalDateTime to, String q) {
    }

    public record Row(Long id, UUID actorId, String action, Long groupId, UUID targetUserId, String details,
                      LocalDateTime createdAt) {
    }

    public Mono<Long> count(Filter filter) {
        return bind(databaseClient.sql("SELECT COUNT(*) FROM group_audit_log WHERE " + condition(filter)), filter)
                .map(row -> row.get(0, Long.class))
                .one();
    }

    public Flux<Row> findNewest(Filter filter, int limit) {
        return bind(databaseClient.sql("""
                        SELECT id, actor_id, action, group_id, target_user_id, details, created_at
                        FROM group_audit_log
                        WHERE %s
                        ORDER BY created_at DESC, id DESC
                        LIMIT :limit
                        """.formatted(condition(filter))), filter)
                .bind("limit", limit)
                .map(row -> new Row(
                        row.get("id", Long.class),
                        row.get("actor_id", UUID.class),
                        row.get("action", String.class),
                        row.get("group_id", Long.class),
                        row.get("target_user_id", UUID.class),
                        row.get("details", String.class),
                        row.get("created_at", LocalDateTime.class)))
                .all();
    }

    // Only fixed text is put into the statement; every value is bound
    private String condition(Filter filter) {
        StringBuilder where = new StringBuilder("1 = 1");
        if (filter.action() != null) {
            where.append(" AND action = :action");
        }
        if (filter.actorId() != null) {
            where.append(" AND actor_id = :actorId");
        }
        if (filter.from() != null) {
            where.append(" AND created_at >= :from");
        }
        if (filter.to() != null) {
            where.append(" AND created_at < :to");
        }
        if (filter.q() != null) {
            where.append(" AND (lower(action) LIKE :q ESCAPE '\\' OR lower(COALESCE(details, '')) LIKE :q ESCAPE '\\')");
        }
        return where.toString();
    }

    private DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec spec, Filter filter) {
        if (filter.action() != null) {
            spec = spec.bind("action", filter.action());
        }
        if (filter.actorId() != null) {
            spec = spec.bind("actorId", filter.actorId());
        }
        if (filter.from() != null) {
            spec = spec.bind("from", filter.from());
        }
        if (filter.to() != null) {
            spec = spec.bind("to", filter.to());
        }
        if (filter.q() != null) {
            String q = filter.q().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            spec = spec.bind("q", "%" + q + "%");
        }
        return spec;
    }
}
