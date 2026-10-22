package com.examportal.exam.repository;

import com.examportal.common.domain.entity.QuestionType;
import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Analytics Repository
 * Aggregates for the analytics screens: read only, a fixed number of statements per screen however
 * many sessions there are.
 *
 * "best" is each participant's best completed attempt of one exam that does not wait for a grader:
 * the highest percentage, the earlier one among equals. It is the base of every score figure.
 */
@Repository
@RequiredArgsConstructor
public class AnalyticsRepository {

    private static final String BEST = """
            best AS (
                SELECT b.id, b.user_id, b.percentage, b.passed, b.start_time, b.submitted_at
                FROM (SELECT s.id, s.user_id, s.percentage, s.passed, s.start_time, s.submitted_at,
                             ROW_NUMBER() OVER (PARTITION BY s.user_id
                                                ORDER BY s.percentage DESC NULLS LAST, s.submitted_at ASC NULLS LAST,
                                                         s.id ASC) AS rn
                      FROM exam_sessions s
                      WHERE s.exam_id = :examId AND s.status = 'COMPLETED' AND s.grading_status <> 'PENDING') b
                WHERE b.rn = 1
            )
            """;

    private final DatabaseClient databaseClient;

    /**
     * One question with its counts over the best attempts. participants and pendingSessions are the
     * same on every row. upperCorrect / lowerCorrect: correct answers among the top / bottom 27 %
     * of the participants by total percentage (at least one participant each).
     */
    public record QuestionStats(Long questionId, Integer orderIndex, String text, QuestionType type,
                                BigDecimal maxPoints, int participants, int pendingSessions, int answered,
                                int correct, int partial, BigDecimal earned, int upperCorrect, int lowerCorrect,
                                int graded, BigDecimal averageGradePercent, int pendingAnswers) {
    }

    public record OptionStats(Long optionId, Long questionId, String text, boolean correct, int selected) {
    }

    public record TextAnswerStats(Long questionId, String text, int count, boolean correct) {
    }

    public record ExamFigures(int ranked, int totalParticipants, int sessions, int completed, int inProgress,
                              int pending, int expired, Long averageDurationSeconds, BigDecimal averagePercentage,
                              BigDecimal medianPercentage, BigDecimal passRate, int[] buckets,
                              int oneAttempt, int twoAttempts, int threeOrMoreAttempts) {
    }

    public record DayCount(LocalDate day, int count) {
    }

    public Flux<QuestionStats> findQuestionStats(Long examId) {
        return databaseClient.sql("WITH " + BEST + """
                        , grp AS (
                            SELECT b.id,
                                   ROW_NUMBER() OVER (ORDER BY b.percentage DESC NULLS LAST, b.id ASC) AS top_rank,
                                   COUNT(*) OVER () AS n
                            FROM best b
                        ), answers AS (
                            SELECT a.question_id, a.is_correct, a.points_earned, a.graded_at, g.top_rank, g.n,
                                   (COALESCE(array_length(a.selected_option_ids, 1), 0) > 0
                                    OR btrim(COALESCE(a.text_answer, '')) <> '') AS filled
                            FROM user_answers a
                            JOIN grp g ON g.id = a.session_id
                        )
                        SELECT q.id, q.order_index, LEFT(q.question_text, 200) AS question_text, q.question_type, q.points,
                               (SELECT COUNT(*) FROM best)::int AS participants,
                               (SELECT COUNT(*) FROM exam_sessions ps
                                WHERE ps.exam_id = :examId AND ps.status = 'COMPLETED'
                                  AND ps.grading_status = 'PENDING')::int AS pending_sessions,
                               COUNT(*) FILTER (WHERE x.filled)::int AS answered,
                               COUNT(*) FILTER (WHERE x.is_correct)::int AS correct,
                               COUNT(*) FILTER (WHERE x.points_earned > 0 AND x.points_earned < q.points)::int AS partial,
                               COALESCE(SUM(x.points_earned), 0) AS earned,
                               COUNT(*) FILTER (WHERE x.is_correct
                                                AND x.top_rank <= GREATEST(1, (27 * x.n + 50) / 100))::int AS upper_correct,
                               COUNT(*) FILTER (WHERE x.is_correct
                                                AND x.top_rank > x.n - GREATEST(1, (27 * x.n + 50) / 100))::int AS lower_correct,
                               COUNT(*) FILTER (WHERE x.graded_at IS NOT NULL)::int AS graded,
                               ROUND(AVG(x.points_earned * 100 / NULLIF(q.points, 0))
                                     FILTER (WHERE x.graded_at IS NOT NULL), 2) AS average_grade_percent,
                               (SELECT COUNT(*) FROM user_answers p
                                JOIN exam_sessions ps ON ps.id = p.session_id
                                WHERE p.question_id = q.id AND ps.status = 'COMPLETED'
                                  AND p.needs_manual_review)::int AS pending_answers
                        FROM questions q
                        LEFT JOIN answers x ON x.question_id = q.id
                        WHERE q.exam_id = :examId AND q.active = true
                        GROUP BY q.id
                        ORDER BY q.order_index ASC, q.id ASC
                        """)
                .bind("examId", examId)
                .map(row -> new QuestionStats(
                        row.get("id", Long.class),
                        row.get("order_index", Integer.class),
                        row.get("question_text", String.class),
                        QuestionType.valueOf(row.get("question_type", String.class)),
                        row.get("points", BigDecimal.class),
                        number(row, "participants"),
                        number(row, "pending_sessions"),
                        number(row, "answered"),
                        number(row, "correct"),
                        number(row, "partial"),
                        row.get("earned", BigDecimal.class),
                        number(row, "upper_correct"),
                        number(row, "lower_correct"),
                        number(row, "graded"),
                        row.get("average_grade_percent", BigDecimal.class),
                        number(row, "pending_answers")))
                .all();
    }

    /**
     * Every option of the exam's choice questions with how many best attempts selected it
     */
    public Flux<OptionStats> findOptionStats(Long examId) {
        return databaseClient.sql("WITH " + BEST + """
                        , picked AS (
                            SELECT unnest(a.selected_option_ids) AS option_id
                            FROM user_answers a
                            JOIN best b ON b.id = a.session_id
                        )
                        SELECT o.id, o.question_id, LEFT(o.option_text, 200) AS option_text, o.is_correct,
                               COUNT(p.option_id)::int AS selected
                        FROM question_options o
                        JOIN questions q ON q.id = o.question_id
                        LEFT JOIN picked p ON p.option_id = o.id
                        WHERE q.exam_id = :examId AND q.active = true
                          AND q.question_type IN ('MULTIPLE_CHOICE', 'TRUE_FALSE')
                        GROUP BY o.id
                        ORDER BY o.question_id ASC, o.order_index ASC, o.id ASC
                        """)
                .bind("examId", examId)
                .map(row -> new OptionStats(
                        row.get("id", Long.class),
                        row.get("question_id", Long.class),
                        row.get("option_text", String.class),
                        Boolean.TRUE.equals(row.get("is_correct", Boolean.class)),
                        number(row, "selected")))
                .all();
    }

    /**
     * The ten most frequent answers (trimmed, lower case) of every fill-in-the-blank question
     */
    public Flux<TextAnswerStats> findTopTextAnswers(Long examId) {
        return databaseClient.sql("WITH " + BEST + """
                        , given AS (
                            SELECT a.question_id, LEFT(lower(btrim(a.text_answer)), 200) AS answer,
                                   COUNT(*)::int AS times, BOOL_OR(COALESCE(a.is_correct, false)) AS correct
                            FROM user_answers a
                            JOIN best b ON b.id = a.session_id
                            JOIN questions q ON q.id = a.question_id
                            WHERE q.exam_id = :examId AND q.active = true AND q.question_type IN ('FILL_BLANK', 'SHORT_ANSWER', 'LINEAR_SCALE')
                              AND btrim(COALESCE(a.text_answer, '')) <> ''
                            GROUP BY a.question_id, LEFT(lower(btrim(a.text_answer)), 200)
                        ), ranked AS (
                            SELECT g.*, ROW_NUMBER() OVER (PARTITION BY g.question_id ORDER BY g.times DESC, g.answer ASC) AS rn
                            FROM given g
                        )
                        SELECT question_id, answer, times, correct FROM ranked
                        WHERE rn <= 10
                        ORDER BY question_id ASC, rn ASC
                        """)
                .bind("examId", examId)
                .map(row -> new TextAnswerStats(
                        row.get("question_id", Long.class),
                        row.get("answer", String.class),
                        number(row, "times"),
                        Boolean.TRUE.equals(row.get("correct", Boolean.class))))
                .all();
    }

    /**
     * The figures of one exam in one row
     */
    public Mono<ExamFigures> findExamFigures(Long examId) {
        StringBuilder buckets = new StringBuilder();
        for (int bucket = 0; bucket < 10; bucket++) {
            // 100 belongs to the last bucket
            buckets.append(", COUNT(*) FILTER (WHERE LEAST(FLOOR(COALESCE(percentage, 0) / 10), 9) = ")
                    .append(bucket).append(")::int AS bucket_").append(bucket);
        }
        return databaseClient.sql("WITH " + BEST + """
                        , per_user AS (
                            SELECT user_id, COUNT(*) AS attempts FROM exam_sessions WHERE exam_id = :examId GROUP BY user_id
                        )
                        SELECT s.*, b.*, u.*
                        FROM (SELECT COUNT(*)::int AS sessions,
                                     COUNT(*) FILTER (WHERE status = 'COMPLETED')::int AS completed,
                                     COUNT(*) FILTER (WHERE status = 'IN_PROGRESS')::int AS in_progress,
                                     COUNT(*) FILTER (WHERE status = 'COMPLETED' AND grading_status = 'PENDING')::int AS pending,
                                     COUNT(*) FILTER (WHERE status = 'COMPLETED' AND submitted_at >= end_time)::int AS expired
                              FROM exam_sessions WHERE exam_id = :examId) s
                        CROSS JOIN (SELECT COUNT(*)::int AS ranked,
                                           ROUND(AVG(EXTRACT(EPOCH FROM (submitted_at - start_time))))::bigint AS average_duration,
                                           ROUND(AVG(percentage), 2) AS average_percentage,
                                           ROUND((PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY percentage))::numeric, 2) AS median_percentage,
                                           ROUND(100.0 * COUNT(*) FILTER (WHERE passed) / NULLIF(COUNT(*), 0), 2) AS pass_rate
                                           %s
                                    FROM best) b
                        CROSS JOIN (SELECT COUNT(*)::int AS total_participants,
                                           COUNT(*) FILTER (WHERE attempts = 1)::int AS one_attempt,
                                           COUNT(*) FILTER (WHERE attempts = 2)::int AS two_attempts,
                                           COUNT(*) FILTER (WHERE attempts >= 3)::int AS three_or_more
                                    FROM per_user) u
                        """.formatted(buckets))
                .bind("examId", examId)
                .map(row -> {
                    int[] counts = new int[10];
                    for (int bucket = 0; bucket < counts.length; bucket++) {
                        counts[bucket] = number(row, "bucket_" + bucket);
                    }
                    return new ExamFigures(
                            number(row, "ranked"),
                            number(row, "total_participants"),
                            number(row, "sessions"),
                            number(row, "completed"),
                            number(row, "in_progress"),
                            number(row, "pending"),
                            number(row, "expired"),
                            row.get("average_duration", Long.class),
                            row.get("average_percentage", BigDecimal.class),
                            row.get("median_percentage", BigDecimal.class),
                            row.get("pass_rate", BigDecimal.class),
                            counts,
                            number(row, "one_attempt"),
                            number(row, "two_attempts"),
                            number(row, "three_or_more"));
                })
                .one();
    }

    /**
     * Sessions of one exam started per calendar day of the given zone, every day from firstDay to
     * lastDay (days without a session included). fromUtc is the UTC moment firstDay starts.
     */
    public Flux<DayCount> findSessionsPerDay(Long examId, String zone, LocalDate firstDay, LocalDate lastDay,
                                             LocalDateTime fromUtc) {
        return databaseClient.sql("""
                        SELECT d.day::date AS day, COALESCE(c.sessions, 0)::int AS sessions
                        FROM generate_series(CAST(:firstDay AS date), CAST(:lastDay AS date), interval '1 day') AS d(day)
                        LEFT JOIN (SELECT ((s.start_time AT TIME ZONE 'UTC') AT TIME ZONE :zone)::date AS day,
                                          COUNT(*) AS sessions
                                   FROM exam_sessions s
                                   WHERE s.exam_id = :examId AND s.start_time >= :fromUtc
                                   GROUP BY 1) c ON c.day = d.day::date
                        ORDER BY 1
                        """)
                .bind("examId", examId)
                .bind("zone", zone)
                .bind("firstDay", firstDay)
                .bind("lastDay", lastDay)
                .bind("fromUtc", fromUtc)
                .map(row -> new DayCount(row.get("day", LocalDate.class), number(row, "sessions")))
                .all();
    }

    // ==================== Platform ====================

    public record StatusCount(String status, int count) {
    }

    public record PlatformFigures(int activeNow, int pendingGrading, BigDecimal averagePercentage, BigDecimal passRate) {
    }

    public record PlatformDay(LocalDate day, int sessions, int participants) {
    }

    public record TopExam(Long examId, String title, String status, int sessions, int participants) {
    }

    /**
     * Exams that are not deleted, per status
     */
    public Flux<StatusCount> countExamsByStatus() {
        return databaseClient.sql("SELECT status, COUNT(*)::int AS exams FROM exams WHERE active = true GROUP BY status")
                .map(row -> new StatusCount(row.get("status", String.class), number(row, "exams")))
                .all();
    }

    /**
     * Sessions running now, sessions waiting for a grader, and the average percentage and pass rate
     * over every participant's best fully graded attempt per exam
     */
    public Mono<PlatformFigures> findPlatformFigures(LocalDateTime now) {
        return databaseClient.sql("""
                        WITH best AS (
                            SELECT b.percentage, b.passed
                            FROM (SELECT s.percentage, s.passed,
                                         ROW_NUMBER() OVER (PARTITION BY s.exam_id, s.user_id
                                                            ORDER BY s.percentage DESC NULLS LAST, s.submitted_at ASC NULLS LAST,
                                                                     s.id ASC) AS rn
                                  FROM exam_sessions s
                                  WHERE s.status = 'COMPLETED' AND s.grading_status <> 'PENDING') b
                            WHERE b.rn = 1
                        )
                        SELECT (SELECT COUNT(*) FROM exam_sessions
                                WHERE status = 'IN_PROGRESS' AND end_time > :now)::int AS active_now,
                               (SELECT COUNT(*) FROM exam_sessions
                                WHERE status = 'COMPLETED' AND grading_status = 'PENDING')::int AS pending_grading,
                               ROUND(AVG(percentage), 2) AS average_percentage,
                               ROUND(100.0 * COUNT(*) FILTER (WHERE passed) / NULLIF(COUNT(*), 0), 2) AS pass_rate
                        FROM best
                        """)
                .bind("now", now)
                .map(row -> new PlatformFigures(
                        number(row, "active_now"),
                        number(row, "pending_grading"),
                        row.get("average_percentage", BigDecimal.class),
                        row.get("pass_rate", BigDecimal.class)))
                .one();
    }

    /**
     * Sessions started and different participants per calendar day of the given zone, every day
     * from firstDay to lastDay (idx_sessions_start_time)
     */
    public Flux<PlatformDay> findPlatformDays(String zone, LocalDate firstDay, LocalDate lastDay, LocalDateTime fromUtc) {
        return databaseClient.sql("""
                        SELECT d.day::date AS day, COALESCE(c.sessions, 0)::int AS sessions,
                               COALESCE(c.participants, 0)::int AS participants
                        FROM generate_series(CAST(:firstDay AS date), CAST(:lastDay AS date), interval '1 day') AS d(day)
                        LEFT JOIN (SELECT ((s.start_time AT TIME ZONE 'UTC') AT TIME ZONE :zone)::date AS day,
                                          COUNT(*) AS sessions, COUNT(DISTINCT s.user_id) AS participants
                                   FROM exam_sessions s
                                   WHERE s.start_time >= :fromUtc
                                   GROUP BY 1) c ON c.day = d.day::date
                        ORDER BY 1
                        """)
                .bind("zone", zone)
                .bind("firstDay", firstDay)
                .bind("lastDay", lastDay)
                .bind("fromUtc", fromUtc)
                .map(row -> new PlatformDay(row.get("day", LocalDate.class), number(row, "sessions"), number(row, "participants")))
                .all();
    }

    /**
     * The exams with the most sessions started since fromUtc
     */
    public Flux<TopExam> findTopExams(LocalDateTime fromUtc, int limit) {
        return databaseClient.sql("""
                        SELECT x.id, x.title, x.status, c.sessions::int AS sessions, c.participants::int AS participants
                        FROM (SELECT s.exam_id, COUNT(*) AS sessions, COUNT(DISTINCT s.user_id) AS participants
                              FROM exam_sessions s
                              WHERE s.start_time >= :fromUtc
                              GROUP BY s.exam_id
                              ORDER BY COUNT(*) DESC, s.exam_id ASC
                              LIMIT :limit) c
                        JOIN exams x ON x.id = c.exam_id
                        ORDER BY c.sessions DESC, x.id ASC
                        """)
                .bind("fromUtc", fromUtc)
                .bind("limit", limit)
                .map(row -> new TopExam(
                        row.get("id", Long.class),
                        row.get("title", String.class),
                        row.get("status", String.class),
                        number(row, "sessions"),
                        number(row, "participants")))
                .all();
    }

    // ==================== Private Helper Methods ====================

    static int number(Readable row, String column) {
        Integer value = row.get(column, Integer.class);
        return value == null ? 0 : value;
    }
}
