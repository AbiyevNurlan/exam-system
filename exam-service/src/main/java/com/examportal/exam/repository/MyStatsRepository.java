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
    public Mono<MyStatsResponse> forUser(UUID userId, LocalDateTime now) {
        return databaseClient.sql("""
                        WITH mine AS (
                            SELECT s.exam_id, s.percentage, s.passed, s.submitted_at, s.grading_status, e.title,
                                   (s.grading_status <> 'PENDING'
                                    AND (COALESCE(e.show_results_immediately, false) OR e.end_time <= :now)) AS visible
                            FROM exam_sessions s
                            JOIN exams e ON e.id = s.exam_id
                            WHERE s.user_id = :userId AND s.status = 'COMPLETED'
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
                .bind("now", now)
                .map(row -> {
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
