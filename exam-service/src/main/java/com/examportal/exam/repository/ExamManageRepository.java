package com.examportal.exam.repository;

import com.examportal.common.domain.dto.ManagedExamResponse;
import com.examportal.common.domain.entity.ExamAccessType;
import com.examportal.common.domain.entity.ExamStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Exam Manage Repository
 * The management list of exams. One page is ONE statement: the points and the session figures of
 * the listed exams are read by the same query, never one query per exam.
 */
@Repository
@RequiredArgsConstructor
public class ExamManageRepository {

    private final DatabaseClient databaseClient;

    /**
     * What the list is narrowed to. ownerId null = every owner (ADMIN). status null = everything
     * except ARCHIVED, which is only listed when asked for. titlePattern null = no search; otherwise
     * an ILIKE pattern whose % and _ from the user are already escaped with a backslash.
     */
    public record Filter(UUID ownerId, ExamStatus status, String titlePattern) {
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
                               COALESCE(p.total_points, 0) AS total_points,
                               COALESCE(s.session_count, 0) AS session_count,
                               COALESCE(s.participant_count, 0) AS participant_count
                        FROM (SELECT e.* FROM exams e WHERE %s
                              ORDER BY e.created_at DESC, e.id DESC LIMIT :limit OFFSET :offset) e
                        LEFT JOIN LATERAL (SELECT SUM(q.points) AS total_points
                                           FROM questions q WHERE q.exam_id = e.id AND q.active = true) p ON true
                        LEFT JOIN LATERAL (SELECT COUNT(*) AS session_count,
                                                  COUNT(DISTINCT x.user_id) AS participant_count
                                           FROM exam_sessions x WHERE x.exam_id = e.id) s ON true
                        ORDER BY e.created_at DESC, e.id DESC
                        """.formatted(condition(filter)))
                .bind("limit", limit)
                .bind("offset", offset);

        return bind(spec, filter)
                .map(row -> ManagedExamResponse.builder()
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
                        .build())
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
        return spec;
    }
}
