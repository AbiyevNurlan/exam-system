package com.examportal.exam.repository;

import com.examportal.common.domain.entity.GradingStatus;
import com.examportal.common.domain.entity.QuestionType;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Grading Repository
 * Manual grading of open-ended answers: the queue of an exam, the answers of one session, and the
 * statements that store a grade and bring the session's score up to date.
 */
@Repository
@RequiredArgsConstructor
public class GradingRepository {

    private final DatabaseClient databaseClient;

    /**
     * One session in the queue. openCount: open-ended answers with text; pendingCount: of those,
     * waiting for a grader; gradedCount: graded by a person.
     */
    public record QueueRow(String sessionId, UUID userId, int attemptNumber, LocalDateTime submittedAt,
                           int openCount, int pendingCount, int gradedCount, GradingStatus gradingStatus) {
    }

    /**
     * One answer to an open-ended question, with what the question is worth
     */
    public record OpenAnswer(Long answerId, Long questionId, String questionText, BigDecimal maxPoints,
                             String textAnswer, BigDecimal pointsEarned, String feedback, UUID gradedBy,
                             LocalDateTime gradedAt, int gradeVersion, boolean needsManualReview) {
    }

    /**
     * An answer a grade was sent for: what kind of question it answers, what that is worth, and
     * what is stored now
     */
    public record GradeTarget(Long answerId, Long questionId, QuestionType questionType, BigDecimal maxPoints,
                              BigDecimal pointsEarned, int gradeVersion) {
    }

    /**
     * Which sessions the queue lists. status null = both PENDING and COMPLETE (every session that
     * needed a grader). userIds null = every participant; otherwise only these users.
     */
    public record QueueFilter(Long examId, GradingStatus status, UUID[] userIds) {
    }

    /**
     * One page of the queue, oldest submission first. One statement: the figures of the listed
     * sessions are read by the same query.
     */
    public Flux<QueueRow> findQueuePage(QueueFilter filter, int limit, long offset) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        SELECT s.session_id, s.user_id, s.submitted_at, s.grading_status,
                               (SELECT COUNT(*) FROM exam_sessions p
                                WHERE p.exam_id = s.exam_id AND p.user_id = s.user_id
                                  AND (p.start_time, p.id) <= (s.start_time, s.id))::int AS attempt_number,
                               COALESCE(c.open_count, 0)::int AS open_count,
                               COALESCE(c.pending_count, 0)::int AS pending_count,
                               COALESCE(c.graded_count, 0)::int AS graded_count
                        FROM (SELECT s.* FROM exam_sessions s WHERE %s
                              ORDER BY s.submitted_at ASC NULLS LAST, s.id ASC LIMIT :limit OFFSET :offset) s
                        LEFT JOIN LATERAL (
                            SELECT COUNT(*) FILTER (WHERE btrim(COALESCE(a.text_answer, '')) <> '') AS open_count,
                                   COUNT(*) FILTER (WHERE a.needs_manual_review) AS pending_count,
                                   COUNT(*) FILTER (WHERE a.graded_at IS NOT NULL) AS graded_count
                            FROM user_answers a
                            JOIN questions q ON q.id = a.question_id AND q.question_type = 'OPEN_ENDED'
                            WHERE a.session_id = s.id) c ON true
                        ORDER BY s.submitted_at ASC NULLS LAST, s.id ASC
                        """.formatted(queueCondition(filter)))
                .bind("limit", limit)
                .bind("offset", offset);

        return bindQueue(spec, filter)
                .map(row -> new QueueRow(
                        row.get("session_id", String.class),
                        row.get("user_id", UUID.class),
                        row.get("attempt_number", Integer.class),
                        row.get("submitted_at", LocalDateTime.class),
                        row.get("open_count", Integer.class),
                        row.get("pending_count", Integer.class),
                        row.get("graded_count", Integer.class),
                        GradingStatus.valueOf(row.get("grading_status", String.class))))
                .all();
    }

    public Mono<Long> countQueue(QueueFilter filter) {
        return bindQueue(databaseClient.sql("SELECT COUNT(*) FROM exam_sessions s WHERE " + queueCondition(filter)), filter)
                .map(row -> row.get(0, Long.class))
                .one();
    }

    /**
     * Every answer of the session to an open-ended question, in question order
     */
    public Flux<OpenAnswer> findOpenAnswers(Long sessionPk) {
        return databaseClient.sql("""
                        SELECT a.id, a.question_id, q.question_text, q.points, a.text_answer, a.points_earned,
                               a.feedback, a.graded_by, a.graded_at, a.grade_version, a.needs_manual_review
                        FROM user_answers a
                        JOIN questions q ON q.id = a.question_id
                        WHERE a.session_id = :sessionPk AND q.question_type = 'OPEN_ENDED'
                        ORDER BY q.order_index, a.id
                        """)
                .bind("sessionPk", sessionPk)
                .map(row -> new OpenAnswer(
                        row.get("id", Long.class),
                        row.get("question_id", Long.class),
                        row.get("question_text", String.class),
                        row.get("points", BigDecimal.class),
                        row.get("text_answer", String.class),
                        row.get("points_earned", BigDecimal.class),
                        row.get("feedback", String.class),
                        row.get("graded_by", UUID.class),
                        row.get("graded_at", LocalDateTime.class),
                        row.get("grade_version", Integer.class),
                        Boolean.TRUE.equals(row.get("needs_manual_review", Boolean.class))))
                .all();
    }

    /**
     * Lock the session row until the end of the surrounding transaction and emit its status, so two
     * gradings of the same session take turns and each sees what the other stored.
     */
    public Mono<String> lockSession(Long sessionPk) {
        return databaseClient.sql("SELECT status FROM exam_sessions WHERE id = :sessionPk FOR NO KEY UPDATE")
                .bind("sessionPk", sessionPk)
                .map(row -> row.get("status", String.class))
                .one();
    }

    /**
     * The answers of this session among the given ids (an id of another session is simply absent)
     */
    public Flux<GradeTarget> findGradeTargets(Long sessionPk, Long[] answerIds) {
        return databaseClient.sql("""
                        SELECT a.id, a.question_id, q.question_type, q.points, a.points_earned, a.grade_version
                        FROM user_answers a
                        JOIN questions q ON q.id = a.question_id
                        WHERE a.session_id = :sessionPk AND a.id = ANY(:answerIds)
                        """)
                .bind("sessionPk", sessionPk)
                .bind("answerIds", answerIds)
                .map(row -> new GradeTarget(
                        row.get("id", Long.class),
                        row.get("question_id", Long.class),
                        QuestionType.valueOf(row.get("question_type", String.class)),
                        row.get("points", BigDecimal.class),
                        row.get("points_earned", BigDecimal.class),
                        row.get("grade_version", Integer.class)))
                .all();
    }

    /**
     * Store one grade, but only while the answer still has the version the grader saw: emits how
     * many rows were written (0 = someone else graded it in the meantime).
     */
    public Mono<Long> applyGrade(Long sessionPk, Long answerId, BigDecimal points, boolean fullMarks, String feedback,
                                 UUID gradedBy, LocalDateTime now, int expectedVersion) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        UPDATE user_answers
                        SET points_earned = :points,
                            is_correct = :fullMarks,
                            feedback = CAST(:feedback AS TEXT),
                            graded_by = :gradedBy,
                            graded_at = :now,
                            grade_version = grade_version + 1,
                            needs_manual_review = false,
                            updated_at = :now
                        WHERE id = :answerId AND session_id = :sessionPk AND grade_version = :expectedVersion
                        """)
                .bind("points", points)
                .bind("fullMarks", fullMarks)
                .bind("gradedBy", gradedBy)
                .bind("now", now)
                .bind("answerId", answerId)
                .bind("sessionPk", sessionPk)
                .bind("expectedVersion", expectedVersion);
        spec = feedback != null ? spec.bind("feedback", feedback) : spec.bindNull("feedback", String.class);
        return spec.fetch().rowsUpdated();
    }

    /**
     * Bring one session's score up to date with its answers, in one statement: score = the points
     * of all its answers, percentage = score against the maximum stored at completion, passed
     * against the exam's passing score; PENDING while an answer still waits for a grader, otherwise
     * COMPLETE. Touches this session only.
     */
    public Mono<Long> recalculateSession(Long sessionPk) {
        return databaseClient.sql("""
                        UPDATE exam_sessions s
                        SET score = t.earned,
                            percentage = t.percentage,
                            passed = t.percentage >= COALESCE(e.passing_score, 60),
                            grading_status = CASE WHEN t.pending > 0 THEN 'PENDING' ELSE 'COMPLETE' END,
                            updated_at = LOCALTIMESTAMP,
                            version = COALESCE(s.version, 0) + 1
                        FROM exams e,
                             (SELECT x.earned, x.pending,
                                     CASE WHEN COALESCE(x.max_points, 0) > 0
                                          THEN ROUND(x.earned * 100 / x.max_points, 2) ELSE 0 END AS percentage
                              FROM (SELECT COALESCE(SUM(a.points_earned), 0) AS earned,
                                           COUNT(*) FILTER (WHERE a.needs_manual_review) AS pending,
                                           (SELECT total_points FROM exam_sessions WHERE id = :sessionPk) AS max_points
                                    FROM user_answers a WHERE a.session_id = :sessionPk) x) t
                        WHERE s.id = :sessionPk AND e.id = s.exam_id
                        """)
                .bind("sessionPk", sessionPk)
                .fetch()
                .rowsUpdated();
    }

    // Only fixed text is put into the statement; every value is bound
    private String queueCondition(QueueFilter filter) {
        StringBuilder condition = new StringBuilder("s.exam_id = :examId AND s.status = 'COMPLETED'");
        condition.append(filter.status() != null
                ? " AND s.grading_status = :gradingStatus"
                : " AND s.grading_status IN ('PENDING', 'COMPLETE')");
        if (filter.userIds() != null) {
            condition.append(" AND s.user_id = ANY(:userIds)");
        }
        return condition.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindQueue(DatabaseClient.GenericExecuteSpec spec, QueueFilter filter) {
        spec = spec.bind("examId", filter.examId());
        if (filter.status() != null) {
            spec = spec.bind("gradingStatus", filter.status().name());
        }
        if (filter.userIds() != null) {
            spec = spec.bind("userIds", filter.userIds());
        }
        return spec;
    }
}
