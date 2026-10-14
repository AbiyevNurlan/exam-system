package com.examportal.auth.repository;

import com.examportal.common.domain.dto.internal.AdminAuditQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Admin Audit Query Repository
 * Reads admin_audit_log for the audit list of exam-service: the count of the matching entries and
 * the newest of them (two statements; idx_admin_audit_log_created_at orders them).
 */
@Repository
@RequiredArgsConstructor
public class AdminAuditQueryRepository {

    private final DatabaseClient databaseClient;

    public Mono<AdminAuditQuery.Response> find(AdminAuditQuery.Request request) {
        int limit = Math.min(Math.max(request.getLimit(), 0), AdminAuditQuery.MAX_LIMIT);
        String where = condition(request);

        Mono<Long> total = bind(databaseClient.sql("SELECT COUNT(*) FROM admin_audit_log WHERE " + where), request)
                .map(row -> row.get(0, Long.class))
                .one();
        Mono<List<AdminAuditQuery.Entry>> entries = limit == 0
                ? Mono.just(List.of())
                : bind(databaseClient.sql("""
                                SELECT id, admin_user_id, action, target_user_id, created_at FROM admin_audit_log
                                WHERE %s
                                ORDER BY created_at DESC NULLS LAST, id DESC
                                LIMIT :limit
                                """.formatted(where)), request)
                        .bind("limit", limit)
                        .map(row -> AdminAuditQuery.Entry.builder()
                                .id(row.get("id", UUID.class))
                                .actorId(row.get("admin_user_id", UUID.class))
                                .action(row.get("action", String.class))
                                .targetUserId(row.get("target_user_id", UUID.class))
                                .createdAt(row.get("created_at", LocalDateTime.class))
                                .build())
                        .all()
                        .collectList();

        return Mono.zip(total, entries)
                .map(found -> AdminAuditQuery.Response.builder().total(found.getT1()).entries(found.getT2()).build());
    }

    // Only fixed text is put into the statement; every value is bound
    private String condition(AdminAuditQuery.Request request) {
        StringBuilder where = new StringBuilder("1 = 1");
        if (request.getAction() != null) {
            where.append(" AND action = :action");
        }
        if (request.getActorId() != null) {
            where.append(" AND admin_user_id = :actorId");
        }
        if (request.getFrom() != null) {
            where.append(" AND created_at >= :from");
        }
        if (request.getTo() != null) {
            where.append(" AND created_at < :to");
        }
        if (request.getQ() != null) {
            where.append(" AND lower(action) LIKE :q ESCAPE '\\'");
        }
        return where.toString();
    }

    private DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec spec, AdminAuditQuery.Request request) {
        if (request.getAction() != null) {
            spec = spec.bind("action", request.getAction());
        }
        if (request.getActorId() != null) {
            spec = spec.bind("actorId", request.getActorId());
        }
        if (request.getFrom() != null) {
            spec = spec.bind("from", request.getFrom());
        }
        if (request.getTo() != null) {
            spec = spec.bind("to", request.getTo());
        }
        if (request.getQ() != null) {
            String q = request.getQ().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            spec = spec.bind("q", "%" + q + "%");
        }
        return spec;
    }
}
