package com.examportal.exam.repository;

import com.examportal.common.domain.entity.FraudEventType;
import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Monitoring Repository
 * Live monitoring: read only. "Running" always means IN_PROGRESS with the session's own end time
 * still ahead; a session that ran out of time is not listed even before the scheduler closes it.
 * Every list is one statement however many sessions run (the fraud figures and the answer counts
 * come from the same query).
 *
 * riskScoreSql is the weighted sum of a session's fraud events as an SQL aggregate over "e.type"
 * and levelRankSql the level rank (0..3) of the column "risk_score" (both from FraudRiskPolicy);
 * they are fixed text, never input.
 */
@Repository
@RequiredArgsConstructor
public class MonitoringRepository {

    private final DatabaseClient databaseClient;

    public enum Sort {
        RISK, VIOLATIONS, STARTED_AT, PROGRESS
    }

    /**
     * One exam with running sessions; countsByLevel[rank] = sessions at that level
     */
    public record ActiveExam(Long examId, String title, LocalDateTime startTime, LocalDateTime endTime,
                             int inProgressCount, long violationCount, int[] countsByLevel,
                             LocalDateTime lastEventAt) {
    }

    /**
     * One running session. The figures of the whole exam (live*) are the same on every row.
     */
    public record LiveSession(String sessionId, UUID userId, int attemptNumber, LocalDateTime startedAt,
                              LocalDateTime deadline, int answeredCount, int violationCount,
                              Map<FraudEventType, Integer> countsByType, int riskScore, int levelRank,
                              LocalDateTime lastEventAt, FraudEventType lastEventType,
                              int liveCount, long liveViolations, int[] liveCountsByLevel, long matching) {
    }

    public record Summary(int liveCount, long liveViolations, int[] countsByLevel) {
    }

    public record Violation(Long id, FraudEventType type, LocalDateTime occurredAt, String details) {
    }

    /**
     * userIds null = every participant; levelRank null = every level
     */
    public record Filter(Long examId, UUID[] userIds, Integer levelRank) {
    }

    /**
     * The exams that have at least one running session, the riskiest first. ownerId null = all
     * exams (ADMIN), otherwise only the exams this user created.
     */
    public Flux<ActiveExam> findActiveExams(UUID ownerId, LocalDateTime now, String riskScoreSql,
                                            String levelRankSql, int limit) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        WITH live AS (
                            SELECT s.id, s.exam_id
                            FROM exam_sessions s
                            WHERE s.status = 'IN_PROGRESS' AND s.end_time > :now
                        ), scored AS (
                            SELECT l.exam_id, v.violation_count, v.risk_score, v.last_event_at
                            FROM live l
                            CROSS JOIN LATERAL (SELECT COUNT(*) AS violation_count, (%s)::int AS risk_score,
                                                       MAX(e.occurred_at) AS last_event_at
                                                FROM session_fraud_events e WHERE e.session_id = l.id) v
                        ), ranked AS (
                            SELECT sc.*, %s AS level_rank FROM scored sc
                        )
                        SELECT x.id, x.title, x.start_time, x.end_time,
                               COUNT(*)::int AS in_progress,
                               COALESCE(SUM(r.violation_count), 0)::bigint AS violations,
                               COUNT(*) FILTER (WHERE r.level_rank = 0)::int AS level_0,
                               COUNT(*) FILTER (WHERE r.level_rank = 1)::int AS level_1,
                               COUNT(*) FILTER (WHERE r.level_rank = 2)::int AS level_2,
                               COUNT(*) FILTER (WHERE r.level_rank = 3)::int AS level_3,
                               MAX(r.last_event_at) AS last_event_at
                        FROM ranked r
                        JOIN exams x ON x.id = r.exam_id
                        WHERE x.active = true%s
                        GROUP BY x.id
                        ORDER BY MAX(r.level_rank) DESC, COUNT(*) DESC, x.id ASC
                        LIMIT :limit
                        """.formatted(riskScoreSql, levelRankSql, ownerId != null ? " AND x.created_by = :ownerId" : ""))
                .bind("now", now)
                .bind("limit", limit);
        if (ownerId != null) {
            spec = spec.bind("ownerId", ownerId);
        }
        return spec.map(row -> new ActiveExam(
                        row.get("id", Long.class),
                        row.get("title", String.class),
                        row.get("start_time", LocalDateTime.class),
                        row.get("end_time", LocalDateTime.class),
                        row.get("in_progress", Integer.class),
                        row.get("violations", Long.class),
                        levels(row, "level_"),
                        row.get("last_event_at", LocalDateTime.class)))
                .all();
    }

    /**
     * One page of the running sessions of an exam. The exam-wide figures are computed before the
     * filter, so they do not change with the search or the risk filter.
     */
    public Flux<LiveSession> findLiveSessions(Filter filter, LocalDateTime now, String riskScoreSql,
                                              String levelRankSql, Sort sort, boolean ascending,
                                              int limit, long offset) {
        String direction = ascending ? "ASC" : "DESC";
        String order = switch (sort) {
            case RISK -> "f.risk_score " + direction + ", f.violation_count " + direction + ", f.id ASC";
            case VIOLATIONS -> "f.violation_count " + direction + ", f.risk_score " + direction + ", f.id ASC";
            case STARTED_AT -> "f.start_time " + direction + ", f.id " + direction;
            case PROGRESS -> "f.answered " + direction + ", f.id ASC";
        };
        StringBuilder typeCounts = new StringBuilder();
        for (FraudEventType type : FraudEventType.values()) {
            typeCounts.append(", COUNT(*) FILTER (WHERE e.type = '").append(type.name()).append("')::int AS ")
                    .append(typeColumn(type));
        }
        StringBuilder where = new StringBuilder("1 = 1");
        if (filter.userIds() != null) {
            where.append(" AND f.user_id = ANY(:userIds)");
        }
        if (filter.levelRank() != null) {
            where.append(" AND f.level_rank = :levelRank");
        }

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        WITH live AS (
                            SELECT s.id, s.session_id, s.user_id, s.start_time, s.end_time,
                                   (SELECT COUNT(*) FROM exam_sessions p
                                    WHERE p.exam_id = s.exam_id AND p.user_id = s.user_id
                                      AND (p.start_time, p.id) <= (s.start_time, s.id))::int AS attempt_number
                            FROM exam_sessions s
                            WHERE s.exam_id = :examId AND s.status = 'IN_PROGRESS' AND s.end_time > :now
                        ), scored AS (
                            SELECT l.*, v.*, a.answered
                            FROM live l
                            CROSS JOIN LATERAL (
                                SELECT COUNT(*)::int AS violation_count, (%s)::int AS risk_score,
                                       MAX(e.occurred_at) AS last_event_at%s
                                FROM session_fraud_events e WHERE e.session_id = l.id) v
                            CROSS JOIN LATERAL (
                                SELECT COUNT(*)::int AS answered FROM user_answers a
                                WHERE a.session_id = l.id
                                  AND (COALESCE(array_length(a.selected_option_ids, 1), 0) > 0
                                       OR btrim(COALESCE(a.text_answer, '')) <> '')) a
                        ), ranked AS (
                            SELECT sc.*, %s AS level_rank FROM scored sc
                        ), figures AS (
                            SELECT r.*,
                                   COUNT(*) OVER ()::int AS live_count,
                                   COALESCE(SUM(r.violation_count) OVER (), 0)::bigint AS live_violations,
                                   COUNT(*) FILTER (WHERE r.level_rank = 0) OVER ()::int AS live_level_0,
                                   COUNT(*) FILTER (WHERE r.level_rank = 1) OVER ()::int AS live_level_1,
                                   COUNT(*) FILTER (WHERE r.level_rank = 2) OVER ()::int AS live_level_2,
                                   COUNT(*) FILTER (WHERE r.level_rank = 3) OVER ()::int AS live_level_3
                            FROM ranked r
                        )
                        SELECT f.*, COUNT(*) OVER () AS matching,
                               (SELECT z.type FROM session_fraud_events z WHERE z.session_id = f.id
                                ORDER BY z.occurred_at DESC, z.id DESC LIMIT 1) AS last_event_type
                        FROM figures f
                        WHERE %s
                        ORDER BY %s
                        LIMIT :limit OFFSET :offset
                        """.formatted(riskScoreSql, typeCounts, levelRankSql, where, order))
                .bind("examId", filter.examId())
                .bind("now", now)
                .bind("limit", limit)
                .bind("offset", offset);
        if (filter.userIds() != null) {
            spec = spec.bind("userIds", filter.userIds());
        }
        if (filter.levelRank() != null) {
            spec = spec.bind("levelRank", filter.levelRank());
        }
        return spec.map(row -> {
                    Map<FraudEventType, Integer> countsByType = new EnumMap<>(FraudEventType.class);
                    for (FraudEventType type : FraudEventType.values()) {
                        Integer count = row.get(typeColumn(type), Integer.class);
                        if (count != null && count > 0) {
                            countsByType.put(type, count);
                        }
                    }
                    String lastType = row.get("last_event_type", String.class);
                    return new LiveSession(
                            row.get("session_id", String.class),
                            row.get("user_id", UUID.class),
                            row.get("attempt_number", Integer.class),
                            row.get("start_time", LocalDateTime.class),
                            row.get("end_time", LocalDateTime.class),
                            row.get("answered", Integer.class),
                            row.get("violation_count", Integer.class),
                            countsByType,
                            row.get("risk_score", Integer.class),
                            row.get("level_rank", Integer.class),
                            row.get("last_event_at", LocalDateTime.class),
                            lastType != null ? FraudEventType.valueOf(lastType) : null,
                            row.get("live_count", Integer.class),
                            row.get("live_violations", Long.class),
                            levels(row, "live_level_"),
                            row.get("matching", Long.class));
                })
                .all();
    }

    /**
     * The exam-wide figures alone, for a page that came back empty
     */
    public Mono<Summary> summary(Long examId, LocalDateTime now, String riskScoreSql, String levelRankSql) {
        return databaseClient.sql("""
                        WITH scored AS (
                            SELECT v.violation_count, v.risk_score
                            FROM exam_sessions s
                            CROSS JOIN LATERAL (SELECT COUNT(*) AS violation_count, (%s)::int AS risk_score
                                                FROM session_fraud_events e WHERE e.session_id = s.id) v
                            WHERE s.exam_id = :examId AND s.status = 'IN_PROGRESS' AND s.end_time > :now
                        ), ranked AS (
                            SELECT sc.*, %s AS level_rank FROM scored sc
                        )
                        SELECT COUNT(*)::int AS live_count,
                               COALESCE(SUM(r.violation_count), 0)::bigint AS live_violations,
                               COUNT(*) FILTER (WHERE r.level_rank = 0)::int AS live_level_0,
                               COUNT(*) FILTER (WHERE r.level_rank = 1)::int AS live_level_1,
                               COUNT(*) FILTER (WHERE r.level_rank = 2)::int AS live_level_2,
                               COUNT(*) FILTER (WHERE r.level_rank = 3)::int AS live_level_3
                        FROM ranked r
                        """.formatted(riskScoreSql, levelRankSql))
                .bind("examId", examId)
                .bind("now", now)
                .map(row -> new Summary(
                        row.get("live_count", Integer.class),
                        row.get("live_violations", Long.class),
                        levels(row, "live_level_")))
                .one();
    }

    public Mono<Integer> countActiveQuestions(Long examId) {
        return databaseClient.sql("SELECT COUNT(*)::int FROM questions WHERE exam_id = :examId AND active = true")
                .bind("examId", examId)
                .map(row -> row.get(0, Integer.class))
                .one();
    }

    /**
     * The fraud events of one session with an id above sinceId, in id order
     * (idx_session_fraud_events_session_id)
     */
    public Flux<Violation> findViolations(Long sessionPk, long sinceId, int limit) {
        return databaseClient.sql("""
                        SELECT id, type, occurred_at, details FROM session_fraud_events
                        WHERE session_id = :sessionPk AND id > :sinceId
                        ORDER BY id ASC
                        LIMIT :limit
                        """)
                .bind("sessionPk", sessionPk)
                .bind("sinceId", sinceId)
                .bind("limit", limit)
                .map(row -> new Violation(
                        row.get("id", Long.class),
                        FraudEventType.valueOf(row.get("type", String.class)),
                        row.get("occurred_at", LocalDateTime.class),
                        row.get("details", String.class)))
                .all();
    }

    // ==================== Private Helper Methods ====================

    private static String typeColumn(FraudEventType type) {
        return "type_" + type.name().toLowerCase(Locale.ROOT);
    }

    private static int[] levels(Readable row, String prefix) {
        int[] counts = new int[4];
        for (int rank = 0; rank < counts.length; rank++) {
            Integer count = row.get(prefix + rank, Integer.class);
            counts[rank] = count == null ? 0 : count;
        }
        return counts;
    }
}
