package com.examportal.exam.history;

import com.examportal.common.domain.dto.history.ExamHistoryDtos;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads exam_revisions (B12a). Pages of one exam's history are read by the unique key (exam_id, revision_no)
 * backwards, filtered rows by the same key; the newest edit of each question by idx_exam_revisions_entity.
 * Only fixed text goes into a statement; every value is bound.
 */
@Repository
@RequiredArgsConstructor
public class ExamHistoryQueryRepository {

    private static final String COLUMNS = """
            id, exam_id, revision_no, save_id, created_at, action, entity_type, entity_id, sensitive, reason, summary_key,
            summary_params::text AS summary_params, actor_user_id, actor_name, actor_code, actor_role
            """;

    private final DatabaseClient databaseClient;

    /**
     * What a history page is narrowed to; null = no filter. actorCode is the 7-digit participant code.
     */
    public record Filter(UUID actorId, Integer actorCode, String action, LocalDateTime from, LocalDateTime to,
                         boolean sensitiveOnly, String entityType) {
    }

    public record LastEdit(Long entityId, UUID actorId, String actorName, Integer actorCode, LocalDateTime at) {
    }

    public Flux<ExamHistoryDtos.Entry> page(Long examId, Filter filter, int limit, long offset) {
        return bind(databaseClient.sql("SELECT " + COLUMNS + " FROM exam_revisions WHERE " + condition(filter)
                        + " ORDER BY revision_no DESC LIMIT :limit OFFSET :offset"), examId, filter)
                .bind("limit", limit)
                .bind("offset", offset)
                .map(row -> entry(row, false))
                .all();
    }

    public Mono<Long> count(Long examId, Filter filter) {
        return bind(databaseClient.sql("SELECT COUNT(*) FROM exam_revisions WHERE " + condition(filter)), examId, filter)
                .map(row -> row.get(0, Long.class))
                .one();
    }

    /**
     * One row with its changes (and, for whoever may see them, ip, user agent and request id)
     */
    public Mono<ExamHistoryDtos.Entry> one(Long examId, Long revisionId, boolean withRequest) {
        return databaseClient.sql("SELECT " + COLUMNS + ", changes::text AS changes, ip, user_agent, request_id "
                        + "FROM exam_revisions WHERE exam_id = :examId AND id = :id")
                .bind("examId", examId)
                .bind("id", revisionId)
                .map(row -> {
                    ExamHistoryDtos.Entry entry = entry(row, true);
                    if (withRequest) {
                        entry.setIp(row.get("ip", String.class));
                        entry.setUserAgent(row.get("user_agent", String.class));
                        entry.setRequestId(row.get("request_id", String.class));
                    }
                    return entry;
                })
                .one();
    }

    /**
     * Who owned the exam according to its newest history row: for an exam that no longer exists
     */
    public Mono<UUID> owner(Long examId) {
        return databaseClient.sql("""
                        SELECT exam_owner_id FROM exam_revisions WHERE exam_id = :examId AND exam_owner_id IS NOT NULL
                        ORDER BY revision_no DESC LIMIT 1
                        """)
                .bind("examId", examId)
                .map(row -> row.get("exam_owner_id", UUID.class))
                .one();
    }

    /**
     * The newest history row of each question of the exam: who edited it last and when
     */
    public Mono<Map<Long, LastEdit>> lastQuestionEdits(Long examId) {
        return databaseClient.sql("""
                        SELECT DISTINCT ON (entity_id) entity_id, actor_user_id, actor_name, actor_code, created_at
                        FROM exam_revisions
                        WHERE exam_id = :examId AND entity_type = 'QUESTION' AND entity_id IS NOT NULL
                        ORDER BY entity_id, revision_no DESC
                        """)
                .bind("examId", examId)
                .map(row -> new LastEdit(row.get("entity_id", Long.class), row.get("actor_user_id", UUID.class),
                        row.get("actor_name", String.class), row.get("actor_code", Integer.class),
                        row.get("created_at", LocalDateTime.class)))
                .all()
                .collectMap(LastEdit::entityId);
    }

    private static String condition(Filter filter) {
        StringBuilder where = new StringBuilder("exam_id = :examId");
        if (filter.actorId() != null) {
            where.append(" AND actor_user_id = :actorId");
        }
        if (filter.actorCode() != null) {
            where.append(" AND actor_code = :actorCode");
        }
        if (filter.action() != null) {
            where.append(" AND action = :action");
        }
        if (filter.from() != null) {
            where.append(" AND created_at >= :from");
        }
        if (filter.to() != null) {
            where.append(" AND created_at < :to");
        }
        if (filter.sensitiveOnly()) {
            where.append(" AND sensitive");
        }
        if (filter.entityType() != null) {
            where.append(" AND entity_type = :entityType");
        }
        return where.toString();
    }

    private static DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec spec, Long examId, Filter filter) {
        spec = spec.bind("examId", examId);
        if (filter.actorId() != null) {
            spec = spec.bind("actorId", filter.actorId());
        }
        if (filter.actorCode() != null) {
            spec = spec.bind("actorCode", filter.actorCode());
        }
        if (filter.action() != null) {
            spec = spec.bind("action", filter.action());
        }
        if (filter.from() != null) {
            spec = spec.bind("from", filter.from());
        }
        if (filter.to() != null) {
            spec = spec.bind("to", filter.to());
        }
        if (filter.entityType() != null) {
            spec = spec.bind("entityType", filter.entityType());
        }
        return spec;
    }

    private static ExamHistoryDtos.Entry entry(Readable row, boolean withChanges) {
        return ExamHistoryDtos.Entry.builder()
                .id(row.get("id", Long.class))
                .examId(row.get("exam_id", Long.class))
                .revisionNo(row.get("revision_no", Integer.class))
                .saveId(row.get("save_id", UUID.class))
                .createdAt(row.get("created_at", LocalDateTime.class))
                .action(row.get("action", String.class))
                .entityType(row.get("entity_type", String.class))
                .entityId(row.get("entity_id", Long.class))
                .sensitive(Boolean.TRUE.equals(row.get("sensitive", Boolean.class)))
                .reason(row.get("reason", String.class))
                .summaryKey(row.get("summary_key", String.class))
                .summaryParams(map(row.get("summary_params", String.class)))
                .actor(ExamHistoryDtos.Actor.builder()
                        .userId(row.get("actor_user_id", UUID.class))
                        .name(row.get("actor_name", String.class))
                        .code(row.get("actor_code", Integer.class))
                        .role(row.get("actor_role", String.class))
                        .build())
                .changes(withChanges ? map(row.get("changes", String.class)) : null)
                .build();
    }

    private static Map<String, Object> map(String json) {
        if (json == null) {
            return new HashMap<>();
        }
        try {
            return ExamRevisionRepository.JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored history row is not JSON", e);
        }
    }

    static List<String> actions() {
        return List.of("EXAM_CREATED", "EXAM_UPDATED", "META_CHANGED", "QUESTION_ADDED", "QUESTION_UPDATED",
                "QUESTION_DELETED", "QUESTION_MOVED", "QUESTION_REORDERED", "OPTION_CHANGED", "CORRECT_ANSWER_CHANGED",
                "POINTS_CHANGED", "BLOCK_ADDED", "BLOCK_UPDATED", "BLOCK_DELETED", "BLOCK_REORDERED", "SETTINGS_CHANGED",
                "AUDIENCE_CHANGED", "RESCHEDULED", "PUBLISHED", "ARCHIVED", "CANCELLED", "DELETE_ATTEMPTED", "CLONED_FROM");
    }
}
