package com.examportal.exam.repository;

import com.examportal.common.domain.dto.MyStatsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The figures of GET /api/me/stats in one statement over the user's own sessions (index on
 * exam_sessions.user_id). The percentage is the one stored when the attempt was completed or graded
 * (AnswerSubmissionService, GradingService): no formula of its own.
 */
@Repository
@RequiredArgsConstructor
public class MyStatsRepository {

    private final DatabaseClient databaseClient;

    /**
     * visible: what the result endpoint would show the user now (AnswerSubmissionService.resultsWithheld):
     * no open answer waits for a grader, and the exam shows results at once or has ended at :now
     */
    /** B13: what a teacher has already (ADMIN: anybody on the platform), in one statement on owner indexes */
    public record Teaching(boolean hasGroups, boolean hasExams, boolean hasMembers) {
    }

    /**
     * Whether owner (null = anybody) has an active group, an exam that is not deleted, and a member in an active group
     */
    public Mono<Teaching> teaching(UUID owner) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                SELECT EXISTS (SELECT 1 FROM user_groups g WHERE g.active = true
                               AND (CAST(:owner AS uuid) IS NULL OR g.owner_id = CAST(:owner AS uuid))) AS has_groups,
                       EXISTS (SELECT 1 FROM exams e WHERE e.active = true
                               AND (CAST(:owner AS uuid) IS NULL OR e.created_by = CAST(:owner AS uuid))) AS has_exams,
                       EXISTS (SELECT 1 FROM user_groups g JOIN user_group_members m ON m.group_id = g.id
                               WHERE g.active = true
                               AND (CAST(:owner AS uuid) IS NULL OR g.owner_id = CAST(:owner AS uuid))) AS has_members
                """);
        return (owner != null ? spec.bind("owner", owner) : spec.bindNull("owner", UUID.class))
                .map(row -> new Teaching(Boolean.TRUE.equals(row.get("has_groups", Boolean.class)),
                        Boolean.TRUE.equals(row.get("has_exams", Boolean.class)),
                        Boolean.TRUE.equals(row.get("has_members", Boolean.class))))
                .one();
    }

    public Mono<MyStatsResponse> forUser(UUID userId, LocalDateTime now) {
        return forUser(userId, null, now);
    }

    /**
     * B11: the same figures over the exams one instructor created (examOwner), for the participant card of
     * an instructor; examOwner = null is every exam (GET /api/me/stats, an administrator's card)
     */
    public Mono<MyStatsResponse> forUser(UUID userId, UUID examOwner, LocalDateTime now) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        WITH mine AS (
                            SELECT s.exam_id, s.percentage, s.passed, s.submitted_at, s.grading_status, e.title,
                                   (s.grading_status <> 'PENDING'
                                    AND (COALESCE(e.show_results_immediately, false) OR e.end_time <= :now)) AS visible
                            FROM exam_sessions s
                            JOIN exams e ON e.id = s.exam_id
                            WHERE s.user_id = :userId AND s.status = 'COMPLETED'
                        """ + (examOwner == null ? "" : "      AND e.created_by = :examOwner\n") + """
                        ),
                        best AS (
                            SELECT exam_id, max(percentage) AS best FROM mine WHERE visible GROUP BY exam_id
                        ),
                        last AS (
                            SELECT exam_id, title, percentage, passed, submitted_at FROM mine
                            WHERE visible ORDER BY submitted_at DESC NULLS LAST LIMIT 1
                        )
                        SELECT (SELECT count(DISTINCT exam_id) FROM mine) AS exams_taken,
                               (SELECT count(*) FROM best) AS exams_graded,
                               (SELECT round(avg(best), 2) FROM best) AS average_percent,
                               (SELECT max(best) FROM best) AS best_percent,
                               (SELECT count(*) FROM mine WHERE grading_status = 'PENDING') AS pending_grading,
                               last.exam_id AS last_exam_id, last.title AS last_title, last.percentage AS last_percentage,
                               last.passed AS last_passed, last.submitted_at AS last_submitted_at
                        FROM (SELECT 1) AS one
                        LEFT JOIN last ON true
                        """)
                .bind("userId", userId)
                .bind("now", now);
        if (examOwner != null) {
            spec = spec.bind("examOwner", examOwner);
        }
        return spec.map(row -> {
                    Long lastExamId = row.get("last_exam_id", Long.class);
                    return MyStatsResponse.builder()
                            .examsTaken(number(row.get("exams_taken", Long.class)))
                            .examsGraded(number(row.get("exams_graded", Long.class)))
                            .averagePercent(row.get("average_percent", BigDecimal.class))
                            .bestPercent(row.get("best_percent", BigDecimal.class))
                            .pendingGradingCount(number(row.get("pending_grading", Long.class)))
                            .lastResult(lastExamId == null ? null : MyStatsResponse.LastResult.builder()
                                    .examId(lastExamId)
                                    .examTitle(row.get("last_title", String.class))
                                    .percentage(row.get("last_percentage", BigDecimal.class))
                                    .passed(row.get("last_passed", Boolean.class))
                                    .submittedAt(row.get("last_submitted_at", LocalDateTime.class))
                                    .build())
                            .build();
                })
                .one();
    }

    private static long number(Long value) {
        return value == null ? 0 : value;
    }
}
