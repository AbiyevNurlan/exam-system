package com.examportal.exam.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Writes exam_revisions (B12a). The numbers of one save are taken in one statement from the exam's counter
 * (exam_revision_counters, its row stays locked until commit), so revision_no runs 1, 2, 3 ... per exam without
 * gaps or doubles, also for two saves at the same moment. Inside the caller's transaction: the rows exist only if
 * the change itself commits. The table is append-only (V17 triggers); nothing here updates or deletes.
 */
@Repository
@RequiredArgsConstructor
public class ExamRevisionRepository {

    static final ObjectMapper JSON = JsonMapper.builder().build();

    private final DatabaseClient databaseClient;

    public Mono<Void> append(Long examId, UUID ownerId, ChangeContext context, List<RevisionDraft> drafts) {
        if (drafts.isEmpty()) {
            return Mono.empty();
        }
        return databaseClient.sql("""
                        INSERT INTO exam_revision_counters (exam_id, last_revision) VALUES (:examId, :count)
                        ON CONFLICT (exam_id) DO UPDATE SET last_revision = exam_revision_counters.last_revision + :count
                        RETURNING last_revision
                        """)
                .bind("examId", examId)
                .bind("count", drafts.size())
                .map(row -> row.get("last_revision", Integer.class))
                .one()
                .flatMapMany(last -> Flux.range(0, drafts.size())
                        .concatMap(i -> insert(examId, last - drafts.size() + 1 + i, ownerId, context, drafts.get(i))))
                .then();
    }

    private Mono<Void> insert(Long examId, int revisionNo, UUID ownerId, ChangeContext context, RevisionDraft draft) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        INSERT INTO exam_revisions (exam_id, revision_no, save_id, exam_owner_id, actor_user_id, actor_code,
                                                    actor_name, actor_role, action, entity_type, entity_id, changes, sensitive,
                                                    reason, summary_key, summary_params, ip, user_agent, request_id)
                        VALUES (:examId, :revisionNo, :saveId, :ownerId, :actorId, :actorCode, :actorName, :actorRole, :action,
                                :entityType, :entityId, :changes, :sensitive, :reason, :summaryKey, :summaryParams, :ip,
                                :userAgent, :requestId)
                        """)
                .bind("examId", examId)
                .bind("revisionNo", revisionNo)
                .bind("saveId", context.saveId())
                .bind("actorId", context.actorId())
                .bind("actorRole", context.role() == null ? "UNKNOWN" : context.role())
                .bind("action", draft.action())
                .bind("entityType", draft.entityType())
                .bind("changes", Json.of(json(draft.changes())))
                .bind("sensitive", draft.sensitive())
                .bind("summaryKey", draft.summaryKey())
                .bind("summaryParams", Json.of(json(draft.summaryParams())));
        spec = bindOrNull(spec, "ownerId", ownerId, UUID.class);
        spec = bindOrNull(spec, "actorCode", context.actorCode(), Integer.class);
        spec = bindOrNull(spec, "actorName", cut(context.actorName(), 200), String.class);
        spec = bindOrNull(spec, "entityId", draft.entityId(), Long.class);
        spec = bindOrNull(spec, "reason", context.reason(), String.class);
        spec = bindOrNull(spec, "ip", cut(context.ip(), 45), String.class);
        spec = bindOrNull(spec, "userAgent", cut(context.userAgent(), 200), String.class);
        spec = bindOrNull(spec, "requestId", cut(context.requestId(), 64), String.class);
        return spec.then();
    }

    static String json(Object value) {
        try {
            return JSON.writeValueAsString(value == null ? java.util.Map.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("History row is not JSON", e);
        }
    }

    private static <T> DatabaseClient.GenericExecuteSpec bindOrNull(DatabaseClient.GenericExecuteSpec spec, String name,
                                                                     T value, Class<T> type) {
        return value != null ? spec.bind(name, value) : spec.bindNull(name, type);
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
