package com.examportal.exam.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Exam Attempt Repository
 * Opening a session against the attempt limits of an exam, and the figures those limits are
 * checked with. Every session counts as an attempt, whatever its status.
 */
@Repository
@RequiredArgsConstructor
public class ExamAttemptRepository {

    private final DatabaseClient databaseClient;

    /**
     * Sessions of one user for one exam: all of them, and those created within the given day
     */
    public record AttemptCounts(int total, int today) {
    }

    /**
     * Open a session only while the user has attempts left, in total (exams.max_attempts) and for
     * the day [dayStart, dayEnd) (exams.max_attempts_per_day, NULL = no daily limit). Both limits
     * are read from the exam row by the same statement that inserts. Emits how many rows were
     * written: 0 when a limit is reached, or when an IN_PROGRESS session already exists for this
     * user and exam (unique index uq_sessions_one_in_progress).
     *
     * Run it inside a transaction: the advisory lock makes the starts of one user for one exam take
     * turns until commit, so the count each one sees already includes the others. The exam row is
     * read FOR SHARE: a start waits for an edit of the exam that holds it FOR UPDATE (ExamEditLock),
     * and an edit that comes later waits for this start and then sees its session.
     */
    public Mono<Long> openSessionIfAttemptsRemain(String sessionId, Long examId, UUID userId,
                                                  LocalDateTime startTime, LocalDateTime endTime,
                                                  String ipAddress, String browserInfo,
                                                  LocalDateTime dayStart, LocalDateTime dayEnd) {
        DatabaseClient.GenericExecuteSpec insert = databaseClient.sql("""
                        INSERT INTO exam_sessions (session_id, exam_id, user_id, start_time, end_time, status,
                                                   ip_address, browser_info, created_at, updated_at, version)
                        SELECT :sessionId, e.id, :userId, :startTime, :endTime, 'IN_PROGRESS',
                               CAST(:ipAddress AS VARCHAR), CAST(:browserInfo AS TEXT), :startTime, :startTime, 0
                        FROM exams e
                        WHERE e.id = :examId
                          AND (SELECT COUNT(*) FROM exam_sessions s
                               WHERE s.exam_id = e.id AND s.user_id = :userId) < COALESCE(e.max_attempts, 1)
                          AND (e.max_attempts_per_day IS NULL
                               OR (SELECT COUNT(*) FROM exam_sessions s
                                   WHERE s.exam_id = e.id AND s.user_id = :userId
                                     AND s.created_at >= :dayStart AND s.created_at < :dayEnd) < e.max_attempts_per_day)
                        FOR SHARE OF e
                        ON CONFLICT (exam_id, user_id) WHERE status = 'IN_PROGRESS' DO NOTHING
                        """)
                .bind("sessionId", sessionId)
                .bind("examId", examId)
                .bind("userId", userId)
                .bind("startTime", startTime)
                .bind("endTime", endTime)
                .bind("dayStart", dayStart)
                .bind("dayEnd", dayEnd);
        insert = ipAddress != null ? insert.bind("ipAddress", ipAddress) : insert.bindNull("ipAddress", String.class);
        insert = browserInfo != null ? insert.bind("browserInfo", browserInfo) : insert.bindNull("browserInfo", String.class);

        return databaseClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))")
                .bind("lockKey", "exam-start:" + examId + ":" + userId)
                .then()
                .then(insert.fetch().rowsUpdated());
    }

    /**
     * B13b: a session of a guest who paid (PAID_OPEN): the attempts are counted by the guest's entitlement, not by the
     * exam's max_attempts, so this insert has no limit of its own; the single active session per exam and user stays
     * (ON CONFLICT). Called inside the caller's transaction, after the lock of lockStart and the attempt taken.
     */
    public Mono<Long> openPaidSession(String sessionId, Long examId, UUID userId, LocalDateTime startTime, LocalDateTime endTime,
                                      String ipAddress, String browserInfo) {
        DatabaseClient.GenericExecuteSpec insert = databaseClient.sql("""
                        INSERT INTO exam_sessions (session_id, exam_id, user_id, start_time, end_time, status,
                                                   ip_address, browser_info, created_at, updated_at, version)
                        VALUES (:sessionId, :examId, :userId, :startTime, :endTime, 'IN_PROGRESS',
                                CAST(:ipAddress AS VARCHAR), CAST(:browserInfo AS TEXT), :startTime, :startTime, 0)
                        ON CONFLICT (exam_id, user_id) WHERE status = 'IN_PROGRESS' DO NOTHING
                        """)
                .bind("sessionId", sessionId)
                .bind("examId", examId)
                .bind("userId", userId)
                .bind("startTime", startTime)
                .bind("endTime", endTime);
        insert = ipAddress != null ? insert.bind("ipAddress", ipAddress) : insert.bindNull("ipAddress", String.class);
        insert = browserInfo != null ? insert.bind("browserInfo", browserInfo) : insert.bindNull("browserInfo", String.class);
        return insert.fetch().rowsUpdated();
    }

    /** B13b: the lock of a start (the same key as openSessionIfAttemptsRemain), for the caller's transaction */
    public Mono<Void> lockStart(Long examId, UUID userId) {
        return databaseClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))")
                .bind("lockKey", "exam-start:" + examId + ":" + userId)
                .then();
    }

    /**
     * How many sessions the user has for the exam, in total and within the day [dayStart, dayEnd)
     */
    public Mono<AttemptCounts> countAttempts(Long examId, UUID userId, LocalDateTime dayStart, LocalDateTime dayEnd) {
        return databaseClient.sql("""
                        SELECT COUNT(*)::int AS total,
                               (COUNT(*) FILTER (WHERE created_at >= :dayStart AND created_at < :dayEnd))::int AS today
                        FROM exam_sessions
                        WHERE exam_id = :examId AND user_id = :userId
                        """)
                .bind("examId", examId)
                .bind("userId", userId)
                .bind("dayStart", dayStart)
                .bind("dayEnd", dayEnd)
                .map(row -> new AttemptCounts(row.get("total", Integer.class), row.get("today", Integer.class)))
                .one();
    }
}
