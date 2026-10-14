package com.examportal.exam.repository;

import com.examportal.common.domain.entity.FraudEventType;
import com.examportal.common.domain.entity.GradingStatus;
import com.examportal.common.domain.entity.SessionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Exam Results Repository
 * The results of one exam for its managers: the list of sessions, the summary figures and the
 * fraud events of one session. A page of the list is two statements (count and page) and the
 * summary is two, however many sessions the exam has; nothing is read per row.
 *
 * riskScoreSql is the weighted sum of a session's fraud events as an SQL aggregate over the type
 * column "e.type" (FraudRiskPolicy.scoreSql); it is fixed text, never input. The list also carries
 * the risk level rank of each session (0 = LOW .. 3 = CRITICAL, FraudRiskPolicy.levelRankSql over
 * the score), so it can be filtered and sorted by level in the database.
 */
@Repository
@RequiredArgsConstructor
public class ExamResultsRepository {

    private final DatabaseClient databaseClient;

    public enum Sort {
        SCORE, VIOLATIONS, SUBMITTED_AT, RISK
    }

    // The column the risk level rank is computed from in the CTE "listed"
    public static final String LISTED_RISK_SCORE = "COALESCE(v.risk_score, 0)";

    /**
     * How a completed session was handed in: MANUAL (submitted before its time was up) or
     * AUTO_EXPIRED (closed at or after the end of its time); null while in progress
     */
    public enum SubmitKind {
        MANUAL, AUTO_EXPIRED
    }

    /**
     * What the list is narrowed to; null = not filtered. userIds: only these users (from a text
     * search). bestOnly: one row per participant, the completed attempt with the highest
     * percentage (the earlier one among equals); the other filters apply to that row.
     */
    public record Filter(Long examId, UUID[] userIds, SessionStatus status, Boolean passed,
                         GradingStatus gradingStatus, Boolean hasViolations, boolean bestOnly,
                         Integer riskRank, BigDecimal minPercent, BigDecimal maxPercent) {

        public Filter(Long examId, UUID[] userIds, SessionStatus status, Boolean passed,
                      GradingStatus gradingStatus, Boolean hasViolations, boolean bestOnly) {
            this(examId, userIds, status, passed, gradingStatus, hasViolations, bestOnly, null, null, null);
        }
    }

    /**
     * The fixed SQL text of the risk score aggregate (over "e.type") and of the level rank (over
     * LISTED_RISK_SCORE)
     */
    public record RiskSql(String score, String rank) {
    }

    public record ResultRow(String sessionId, UUID userId, int attemptNumber, SessionStatus status,
                            LocalDateTime startedAt, LocalDateTime submittedAt, BigDecimal score,
                            BigDecimal maxScore, BigDecimal percentage, Boolean passed,
                            GradingStatus gradingStatus, int violationCount, int riskScore,
                            SubmitKind submitKind) {
    }

    public record Counts(long participantCount, long sessionCount, long completedCount, long inProgressCount,
                         long pendingGradingCount) {
    }

    /**
     * One participant's best fully graded attempt with its place in the four rankings, and the
     * figures over all such attempts (the same on every row)
     */
    public record RankedBest(String sessionId, UUID userId, BigDecimal score, BigDecimal maxScore,
                             BigDecimal percentage, int violationCount, int riskScore,
                             int topRank, int lowRank, int mostRank, int fewestRank,
                             BigDecimal averagePercentage, BigDecimal passRate, long cleanCount, long rankedCount) {
    }

    public record FraudEvent(FraudEventType type, LocalDateTime occurredAt, String details) {
    }

    public record TypeCount(FraudEventType type, int count) {
    }

    /**
     * One page of the list in the given order
     */
    public Flux<ResultRow> findPage(Filter filter, RiskSql riskSql, Sort sort, boolean ascending,
                                    int limit, long offset) {
        String direction = ascending ? "ASC" : "DESC";
        // Sessions without the sorted value (in progress) come last in both directions
        String order = switch (sort) {
            case SCORE -> "l.percentage " + direction + " NULLS LAST, l.score " + direction
                    + " NULLS LAST, l.submitted_at ASC NULLS LAST, l.id ASC";
            case VIOLATIONS -> "l.violation_count " + direction + ", l.risk_score " + direction + ", l.id ASC";
            case SUBMITTED_AT -> "l.submitted_at " + direction + " NULLS LAST, l.id " + direction;
            // The level follows the score, so the score orders both, also inside a level
            case RISK -> "l.risk_rank " + direction + ", l.risk_score " + direction + ", l.violation_count "
                    + direction + ", l.id ASC";
        };
        return bind(databaseClient.sql(listed(filter, riskSql)
                        + " SELECT l.* FROM listed l ORDER BY " + order + " LIMIT :limit OFFSET :offset"), filter)
                .bind("limit", limit)
                .bind("offset", offset)
                .map(row -> new ResultRow(
                        row.get("session_id", String.class),
                        row.get("user_id", UUID.class),
                        row.get("attempt_number", Integer.class),
                        SessionStatus.valueOf(row.get("status", String.class)),
                        row.get("start_time", LocalDateTime.class),
                        row.get("submitted_at", LocalDateTime.class),
                        row.get("score", BigDecimal.class),
                        row.get("total_points", BigDecimal.class),
                        row.get("percentage", BigDecimal.class),
                        row.get("passed", Boolean.class),
                        GradingStatus.valueOf(row.get("grading_status", String.class)),
                        row.get("violation_count", Integer.class),
                        row.get("risk_score", Integer.class),
                        row.get("submit_kind", String.class) != null
                                ? SubmitKind.valueOf(row.get("submit_kind", String.class))
                                : null))
                .all();
    }

    public Mono<Long> count(Filter filter, RiskSql riskSql) {
        return bind(databaseClient.sql(listed(filter, riskSql) + " SELECT COUNT(*) FROM listed"), filter)
                .map(row -> row.get(0, Long.class))
                .one();
    }

    /**
     * Counts over all sessions of the exam
     */
    public Mono<Counts> counts(Long examId) {
        return databaseClient.sql("""
                        SELECT COUNT(DISTINCT user_id) AS participants,
                               COUNT(*) AS sessions,
                               COUNT(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                               COUNT(*) FILTER (WHERE status = 'IN_PROGRESS') AS in_progress,
                               COUNT(*) FILTER (WHERE status = 'COMPLETED' AND grading_status = 'PENDING') AS pending_grading
                        FROM exam_sessions
                        WHERE exam_id = :examId
                        """)
                .bind("examId", examId)
                .map(row -> new Counts(
                        row.get("participants", Long.class),
                        row.get("sessions", Long.class),
                        row.get("completed", Long.class),
                        row.get("in_progress", Long.class),
                        row.get("pending_grading", Long.class)))
                .one();
    }

    /**
     * The participants that appear in a top-3 list of the summary, from each participant's best
     * completed attempt that does not wait for a grader. One statement; at most 12 rows.
     */
    public Flux<RankedBest> findRankedBest(Long examId, String riskScoreSql) {
        return databaseClient.sql("""
                        WITH best AS (
                            SELECT b.* FROM (
                                SELECT s.id, s.session_id, s.user_id, s.score, s.total_points, s.percentage, s.passed,
                                       s.submitted_at,
                                       ROW_NUMBER() OVER (PARTITION BY s.user_id
                                                          ORDER BY s.percentage DESC NULLS LAST, s.submitted_at ASC NULLS LAST,
                                                                   s.id ASC) AS rn
                                FROM exam_sessions s
                                WHERE s.exam_id = :examId AND s.status = 'COMPLETED' AND s.grading_status <> 'PENDING') b
                            WHERE b.rn = 1
                        ), scored AS (
                            SELECT b.*, v.violation_count, v.risk_score
                            FROM best b
                            CROSS JOIN LATERAL (SELECT COUNT(*)::int AS violation_count, (%s)::int AS risk_score
                                                FROM session_fraud_events e WHERE e.session_id = b.id) v
                        ), ranked AS (
                            SELECT sc.*,
                                   ROW_NUMBER() OVER (ORDER BY sc.percentage DESC NULLS LAST, sc.score DESC NULLS LAST,
                                                               sc.submitted_at ASC NULLS LAST, sc.id ASC)::int AS top_rank,
                                   ROW_NUMBER() OVER (ORDER BY sc.percentage ASC NULLS FIRST, sc.score ASC NULLS FIRST,
                                                               sc.submitted_at ASC NULLS LAST, sc.id ASC)::int AS low_rank,
                                   ROW_NUMBER() OVER (ORDER BY sc.violation_count DESC, sc.risk_score DESC,
                                                               sc.percentage ASC NULLS FIRST, sc.id ASC)::int AS most_rank,
                                   ROW_NUMBER() OVER (ORDER BY sc.violation_count ASC, sc.percentage DESC NULLS LAST,
                                                               sc.score DESC NULLS LAST, sc.submitted_at ASC NULLS LAST,
                                                               sc.id ASC)::int AS fewest_rank,
                                   ROUND(AVG(COALESCE(sc.percentage, 0)) OVER (), 2) AS average_percentage,
                                   ROUND(100.0 * COUNT(*) FILTER (WHERE sc.passed) OVER () / COUNT(*) OVER (), 2) AS pass_rate,
                                   COUNT(*) FILTER (WHERE sc.violation_count = 0) OVER () AS clean_count,
                                   COUNT(*) OVER () AS ranked_count
                            FROM scored sc
                        )
                        SELECT * FROM ranked r
                        WHERE r.top_rank <= 3 OR r.low_rank <= 3 OR r.fewest_rank <= 3
                           OR (r.most_rank <= 3 AND r.violation_count > 0)
                        """.formatted(riskScoreSql))
                .bind("examId", examId)
                .map(row -> new RankedBest(
                        row.get("session_id", String.class),
                        row.get("user_id", UUID.class),
                        row.get("score", BigDecimal.class),
                        row.get("total_points", BigDecimal.class),
                        row.get("percentage", BigDecimal.class),
                        row.get("violation_count", Integer.class),
                        row.get("risk_score", Integer.class),
                        row.get("top_rank", Integer.class),
                        row.get("low_rank", Integer.class),
                        row.get("most_rank", Integer.class),
                        row.get("fewest_rank", Integer.class),
                        row.get("average_percentage", BigDecimal.class),
                        row.get("pass_rate", BigDecimal.class),
                        row.get("clean_count", Long.class),
                        row.get("ranked_count", Long.class)))
                .all();
    }

    /**
     * Which of the user's sessions for the exam this one is (1 = the first)
     */
    public Mono<Integer> attemptNumber(Long examId, UUID userId, LocalDateTime startTime, Long sessionPk) {
        return databaseClient.sql("""
                        SELECT COUNT(*)::int FROM exam_sessions p
                        WHERE p.exam_id = :examId AND p.user_id = :userId
                          AND (p.start_time, p.id) <= (:startTime, :sessionPk)
                        """)
                .bind("examId", examId)
                .bind("userId", userId)
                .bind("startTime", startTime)
                .bind("sessionPk", sessionPk)
                .map(row -> row.get(0, Integer.class))
                .one();
    }

    /**
     * The fraud events of one session in the order they happened
     */
    public Flux<FraudEvent> findFraudEvents(Long sessionPk, int limit) {
        return databaseClient.sql("""
                        SELECT type, occurred_at, details FROM session_fraud_events
                        WHERE session_id = :sessionPk
                        ORDER BY occurred_at ASC, id ASC
                        LIMIT :limit
                        """)
                .bind("sessionPk", sessionPk)
                .bind("limit", limit)
                .map(row -> new FraudEvent(
                        FraudEventType.valueOf(row.get("type", String.class)),
                        row.get("occurred_at", LocalDateTime.class),
                        row.get("details", String.class)))
                .all();
    }

    public Flux<TypeCount> countFraudEventsByType(Long sessionPk) {
        return databaseClient.sql("""
                        SELECT type, COUNT(*)::int AS events FROM session_fraud_events
                        WHERE session_id = :sessionPk GROUP BY type
                        """)
                .bind("sessionPk", sessionPk)
                .map(row -> new TypeCount(
                        FraudEventType.valueOf(row.get("type", String.class)),
                        row.get("events", Integer.class)))
                .all();
    }

    // ==================== Private Helper Methods ====================

    /**
     * The CTE "listed": every session of the exam with its attempt number, its rank among the
     * participant's sessions and its fraud figures, narrowed by the filter. Only fixed text is put
     * into the statement; every value is bound.
     */
    private String listed(Filter filter, RiskSql riskSql) {
        StringBuilder where = new StringBuilder("1 = 1");
        if (filter.riskRank() != null) {
            where.append(" AND ").append(riskSql.rank()).append(" = :riskRank");
        }
        // A session without a percentage (in progress) is outside any range
        if (filter.minPercent() != null) {
            where.append(" AND r.percentage >= :minPercent");
        }
        if (filter.maxPercent() != null) {
            where.append(" AND r.percentage <= :maxPercent");
        }
        if (filter.bestOnly()) {
            where.append(" AND r.best_rank = 1 AND r.status = 'COMPLETED'");
        }
        if (filter.status() != null) {
            where.append(" AND r.status = :status");
        }
        if (filter.passed() != null) {
            where.append(filter.passed() ? " AND r.passed = true" : " AND r.passed = false");
        }
        if (filter.gradingStatus() != null) {
            where.append(" AND r.grading_status = :gradingStatus");
        }
        if (filter.hasViolations() != null) {
            where.append(filter.hasViolations()
                    ? " AND COALESCE(v.violation_count, 0) > 0"
                    : " AND COALESCE(v.violation_count, 0) = 0");
        }
        return """
                WITH ranked AS (
                    SELECT s.id, s.session_id, s.user_id, s.status, s.start_time, s.end_time, s.submitted_at, s.score,
                           s.total_points, s.percentage, s.passed, s.grading_status,
                           ROW_NUMBER() OVER (PARTITION BY s.user_id ORDER BY s.start_time ASC, s.id ASC)::int AS attempt_number,
                           ROW_NUMBER() OVER (PARTITION BY s.user_id
                                              ORDER BY (s.status = 'COMPLETED') DESC, s.percentage DESC NULLS LAST,
                                                       s.submitted_at ASC NULLS LAST, s.id ASC) AS best_rank
                    FROM exam_sessions s
                    WHERE s.exam_id = :examId%s
                ), violations AS (
                    SELECT e.session_id, COUNT(*)::int AS violation_count, (%s)::int AS risk_score
                    FROM session_fraud_events e
                    JOIN exam_sessions x ON x.id = e.session_id
                    WHERE x.exam_id = :examId
                    GROUP BY e.session_id
                ), listed AS (
                    SELECT r.*, COALESCE(v.violation_count, 0) AS violation_count, COALESCE(v.risk_score, 0) AS risk_score,
                           %s AS risk_rank,
                           CASE WHEN r.status <> 'COMPLETED' OR r.submitted_at IS NULL THEN NULL
                                WHEN r.end_time IS NOT NULL AND r.submitted_at >= r.end_time THEN 'AUTO_EXPIRED'
                                ELSE 'MANUAL' END AS submit_kind
                    FROM ranked r
                    LEFT JOIN violations v ON v.session_id = r.id
                    WHERE %s
                )
                """.formatted(filter.userIds() != null ? " AND s.user_id = ANY(:userIds)" : "", riskSql.score(),
                riskSql.rank(), where);
    }

    private DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec spec, Filter filter) {
        spec = spec.bind("examId", filter.examId());
        if (filter.userIds() != null) {
            spec = spec.bind("userIds", filter.userIds());
        }
        if (filter.status() != null) {
            spec = spec.bind("status", filter.status().name());
        }
        if (filter.gradingStatus() != null) {
            spec = spec.bind("gradingStatus", filter.gradingStatus().name());
        }
        if (filter.riskRank() != null) {
            spec = spec.bind("riskRank", filter.riskRank());
        }
        if (filter.minPercent() != null) {
            spec = spec.bind("minPercent", filter.minPercent());
        }
        if (filter.maxPercent() != null) {
            spec = spec.bind("maxPercent", filter.maxPercent());
        }
        return spec;
    }
}
