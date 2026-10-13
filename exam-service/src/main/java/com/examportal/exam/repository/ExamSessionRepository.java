package com.examportal.exam.repository;

import com.examportal.common.domain.entity.ExamSession;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Exam Session Repository
 * Reactive repository for ExamSession entity operations.
 */
@Repository
public interface ExamSessionRepository extends R2dbcRepository<ExamSession, Long> {

    /**
     * Find session by session ID
     */
    Mono<ExamSession> findBySessionId(String sessionId);

    /**
     * Find all sessions for an exam
     */
    Flux<ExamSession> findByExamId(Long examId);

    /**
     * Find all sessions for a user
     */
    Flux<ExamSession> findByUserId(UUID userId);

    /**
     * Find sessions for a specific exam and user
     */
    Flux<ExamSession> findByExamIdAndUserId(Long examId, UUID userId);

    /**
     * One page of a user's own sessions, newest first (index idx_sessions_user_start)
     */
    @Query("""
        SELECT * FROM exam_sessions
        WHERE user_id = :userId
        ORDER BY start_time DESC, id DESC
        LIMIT :limit OFFSET :offset
    """)
    Flux<ExamSession> findPageByUserId(UUID userId, int limit, long offset);

    Mono<Long> countByUserId(UUID userId);

    /**
     * Count attempts by user for an exam
     */
    Mono<Long> countByExamIdAndUserId(Long examId, UUID userId);

    /**
     * Find active session for user and exam
     */
    @Query("SELECT * FROM exam_sessions WHERE exam_id = :examId AND user_id = :userId AND status = 'IN_PROGRESS' LIMIT 1")
    Mono<ExamSession> findActiveSession(Long examId, UUID userId);

    // Opening a session against the attempt limits: ExamAttemptRepository.openSessionIfAttemptsRemain

    /**
     * One page of an exam's sessions, most recently submitted first (unsubmitted last)
     */
    @Query("""
        SELECT * FROM exam_sessions
        WHERE exam_id = :examId
          AND (CAST(:status AS VARCHAR) IS NULL OR status = CAST(:status AS VARCHAR))
        ORDER BY submitted_at DESC NULLS LAST, id DESC
        LIMIT :limit OFFSET :offset
    """)
    Flux<ExamSession> findPageByExamId(Long examId, String status, int limit, long offset);

    /**
     * Count the sessions of an exam, optionally only those in one status
     */
    @Query("""
        SELECT COUNT(*) FROM exam_sessions
        WHERE exam_id = :examId
          AND (CAST(:status AS VARCHAR) IS NULL OR status = CAST(:status AS VARCHAR))
    """)
    Mono<Long> countByExamIdAndOptionalStatus(Long examId, String status);

    /**
     * Update session status
     */
    @Modifying
    @Query("UPDATE exam_sessions SET status = :status, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
    Mono<Integer> updateStatus(Long id, String status);

    /**
     * Move the session from IN_PROGRESS to COMPLETED. Returns 1 only for the caller that made the
     * transition, so concurrent submits cannot both complete the same session.
     */
    @Modifying
    @Query("""
        UPDATE exam_sessions
        SET status = 'COMPLETED',
            submitted_at = :submittedAt,
            updated_at = CURRENT_TIMESTAMP,
            version = COALESCE(version, 0) + 1
        WHERE id = :id AND status = 'IN_PROGRESS'
    """)
    Mono<Integer> markCompleted(Long id, LocalDateTime submittedAt);

    /**
     * Store the score of a session completed by markCompleted (same transaction)
     */
    @Modifying
    @Query("""
        UPDATE exam_sessions
        SET score = :score,
            total_points = :totalPoints,
            percentage = :percentage,
            passed = :passed
        WHERE id = :id
    """)
    Mono<Integer> saveScore(Long id, BigDecimal score, BigDecimal totalPoints,
                            BigDecimal percentage, Boolean passed);

    /**
     * Find expired sessions (end time passed but still in progress)
     */
    @Query("SELECT * FROM exam_sessions WHERE status = 'IN_PROGRESS' AND end_time < :now")
    Flux<ExamSession> findExpiredSessions(LocalDateTime now);

    /**
     * Check if any sessions exist for an exam (for delete validation)
     */
    Mono<Boolean> existsByExamId(Long examId);
}
