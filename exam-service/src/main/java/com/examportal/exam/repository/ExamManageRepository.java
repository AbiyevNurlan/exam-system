package com.examportal.exam.repository;

import com.examportal.common.domain.dto.ManagedExamResponse;
import com.examportal.common.domain.dto.history.ExamHistoryDtos;
import com.examportal.common.domain.entity.ExamAccessType;
import com.examportal.common.domain.entity.ExamStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Exam Manage Repository
 * The management list of exams. One page is ONE statement: the points and the session figures of
 * the listed exams are read by the same query, never one query per exam.
 */
@Repository
@RequiredArgsConstructor
public class ExamManageRepository {

    public static final String AUDIENCE = "AUDIENCE";
    public static final String PAID_OPEN = "PAID_OPEN";

    private final DatabaseClient databaseClient;

    /**
     * What the list is narrowed to. ownerId null = every owner (ADMIN). status null = everything
     * except ARCHIVED, which is only listed when asked for. titlePattern null = no search; otherwise
     * an ILIKE pattern whose % and _ from the user are already escaped with a backslash. accessMode null = both
     * kinds; otherwise AUDIENCE or PAID_OPEN (B13b), checked by the caller.
     */
    public record Filter(UUID ownerId, ExamStatus status, String titlePattern, String accessMode) {

        public Filter(UUID ownerId, ExamStatus status, String titlePattern) {
            this(ownerId, status, titlePattern, null);
        }
    }

    /**
     * One page of exams, newest first
     */
    public Flux<ManagedExamResponse> findPage(Filter filter, int limit, long offset) {
        // The page is cut first, so the figures are only computed for the rows that are returned
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        SELECT e.id, e.title, e.status, e.access_type, e.start_time, e.end_time, e.duration_minutes,
                               e.max_attempts, e.max_attempts_per_day::int AS max_attempts_per_day, e.total_questions,
                               e.created_by, e.created_at,
                               e.access_mode, e.price_minor, e.currency, e.seat_limit, e.seats_taken,
                               e.sales_open_at, e.sales_close_at,
                               COALESCE(p.total_points, 0) AS total_points,
                               COALESCE(s.session_count, 0) AS session_count,
                               COALESCE(s.participant_count, 0) AS participant_count,
                               aud.group_ids,
                               lm.actor_user_id AS modified_by, lm.actor_name AS modified_by_name,
                               lm.actor_code AS modified_by_code, lm.actor_role AS modified_by_role,
                               COALESCE(lm.created_at, e.updated_at) AS modified_at
                        FROM (SELECT e.* FROM exams e WHERE %s
                              ORDER BY e.created_at DESC, e.id DESC LIMIT :limit OFFSET :offset) e
                        LEFT JOIN LATERAL (SELECT r.actor_user_id, r.actor_name, r.actor_code, r.actor_role, r.created_at
                                           FROM exam_revisions r WHERE r.exam_id = e.id
                                           ORDER BY r.revision_no DESC LIMIT 1) lm ON true
                        LEFT JOIN LATERAL (SELECT SUM(q.points) AS total_points
                                           FROM questions q WHERE q.exam_id = e.id AND q.active = true) p ON true
                        LEFT JOIN LATERAL (SELECT COUNT(*) AS session_count,
                                                  COUNT(DISTINCT x.user_id) AS participant_count
                                           FROM exam_sessions x WHERE x.exam_id = e.id) s ON true
                        LEFT JOIN LATERAL (SELECT array_agg(a.group_id ORDER BY a.group_id) AS group_ids
                                           FROM exam_audience a
                                           JOIN user_groups g ON g.id = a.group_id AND g.active = true
                                           WHERE a.exam_id = e.id AND e.access_type = 'GROUP') aud ON true
                        ORDER BY e.created_at DESC, e.id DESC
                        """.formatted(condition(filter)))
                .bind("limit", limit)
                .bind("offset", offset);

        return bind(spec, filter)
                .map(row -> {
                    Long[] groupIds = row.get("group_ids", Long[].class);
                    List<Long> audience = groupIds == null ? List.of() : Arrays.asList(groupIds);
                    String accessMode = row.get("access_mode", String.class);
                    boolean paid = PAID_OPEN.equals(accessMode);
                    return ManagedExamResponse.builder()
                        .id(row.get("id", Long.class))
                        .title(row.get("title", String.class))
                        .status(ExamStatus.valueOf(row.get("status", String.class)))
                        .accessType(ExamAccessType.valueOf(row.get("access_type", String.class)))
                        .startTime(row.get("start_time", LocalDateTime.class))
                        .endTime(row.get("end_time", LocalDateTime.class))
                        .durationMinutes(row.get("duration_minutes", Integer.class))
                        .maxAttempts(row.get("max_attempts", Integer.class))
                        .maxAttemptsPerDay(row.get("max_attempts_per_day", Integer.class))
                        .totalQuestions(row.get("total_questions", Integer.class))
                        .totalPoints(row.get("total_points", BigDecimal.class))
                        .sessionCount(row.get("session_count", Long.class))
                        .participantCount(row.get("participant_count", Long.class))
                        .createdBy(row.get("created_by", UUID.class))
                        .createdAt(row.get("created_at", LocalDateTime.class))
                        .audienceGroupCount(audience.size())
                        .groupIds(audience)
                        .lastModifiedBy(row.get("modified_by", UUID.class) == null ? null : ExamHistoryDtos.Actor.builder()
                                .userId(row.get("modified_by", UUID.class))
                                .name(row.get("modified_by_name", String.class))
                                .code(row.get("modified_by_code", Integer.class))
                                .role(row.get("modified_by_role", String.class))
                                .build())
                        .lastModifiedAt(row.get("modified_at", LocalDateTime.class))
                        .accessMode(accessMode)
                        .priceMinor(paid ? row.get("price_minor", Integer.class) : null)
                        .currency(paid ? row.get("currency", String.class) : null)
                        .seatLimit(paid ? row.get("seat_limit", Integer.class) : null)
                        .seatsTaken(paid ? row.get("seats_taken", Integer.class) : null)
                        .salesOpenAt(paid ? row.get("sales_open_at", LocalDateTime.class) : null)
                        .salesCloseAt(paid ? row.get("sales_close_at", LocalDateTime.class) : null)
                        .build();
                })
                .all();
    }

    public Mono<Long> count(Filter filter) {
        return bind(databaseClient.sql("SELECT COUNT(*) FROM exams e WHERE " + condition(filter)), filter)
                .map(row -> row.get(0, Long.class))
                .one();
    }

    // Only fixed text is put into the statement; every value is bound
    private String condition(Filter filter) {
        StringBuilder condition = new StringBuilder("e.active = true");
        if (filter.ownerId() != null) {
            condition.append(" AND e.created_by = :ownerId");
        }
        condition.append(filter.status() != null ? " AND e.status = :status" : " AND e.status <> 'ARCHIVED'");
        if (filter.titlePattern() != null) {
            condition.append(" AND e.title ILIKE :titlePattern ESCAPE '\\'");
        }
        if (filter.accessMode() != null) {
            condition.append(" AND e.access_mode = :accessMode");
        }
        return condition.toString();
    }

    private DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec spec, Filter filter) {
        if (filter.ownerId() != null) {
            spec = spec.bind("ownerId", filter.ownerId());
        }
        if (filter.status() != null) {
            spec = spec.bind("status", filter.status().name());
        }
        if (filter.titlePattern() != null) {
            spec = spec.bind("titlePattern", filter.titlePattern());
        }
        if (filter.accessMode() != null) {
            spec = spec.bind("accessMode", filter.accessMode());
        }
        return spec;
    }
}
