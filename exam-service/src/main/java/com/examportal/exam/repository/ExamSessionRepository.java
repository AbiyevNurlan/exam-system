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
     * B13: the session when the caller may manage its exam (not deleted), the ownership in the SQL condition:
     * admin = every exam, otherwise only an exam created by owner. Empty otherwise, so a session of another
     * owner's exam is the same 404 SESSION_NOT_FOUND as a missing one. Call through ExamAccessPolicy.
     */
    @Query("""
        SELECT s.* FROM exam_sessions s JOIN exams e ON e.id = s.exam_id
        WHERE s.session_id = :sessionId AND e.active IS DISTINCT FROM false AND (:admin OR e.created_by = :owner)
        """)
    Mono<ExamSession> findManageableBySessionId(String sessionId, boolean admin, UUID owner);

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

    // The sessions of one exam for its managers (list, filters, figures): ExamResultsRepository

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
            passed = :passed,
            grading_status = :gradingStatus
        WHERE id = :id
    """)
    Mono<Integer> saveScore(Long id, BigDecimal score, BigDecimal totalPoints,
                            BigDecimal percentage, Boolean passed, String gradingStatus);

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
