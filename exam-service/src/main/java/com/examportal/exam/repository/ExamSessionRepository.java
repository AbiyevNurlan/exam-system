package com.examportal.exam.repository;

import com.examportal.common.domain.entity.ExamSession;
import com.examportal.common.domain.entity.SessionStatus;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
    Flux<ExamSession> findByUserId(Long userId);

    /**
     * Find sessions for a specific exam and user
     */
    Flux<ExamSession> findByExamIdAndUserId(Long examId, Long userId);

    /**
     * Count attempts by user for an exam
     */
    Mono<Long> countByExamIdAndUserId(Long examId, Long userId);

    /**
     * Count completed attempts by user for an exam
     */
    @Query("SELECT COUNT(*) FROM exam_sessions WHERE exam_id = :examId AND user_id = :userId AND status = 'COMPLETED'")
    Mono<Long> countCompletedAttempts(Long examId, Long userId);

    /**
     * Find active session for user and exam
     */
    @Query("SELECT * FROM exam_sessions WHERE exam_id = :examId AND user_id = :userId AND status = 'IN_PROGRESS' LIMIT 1")
    Mono<ExamSession> findActiveSession(Long examId, Long userId);

    /**
     * Check if user has an active session
     */
    Mono<Boolean> existsByExamIdAndUserIdAndStatus(Long examId, Long userId, SessionStatus status);

    /**
     * Update session status
     */
    @Modifying
    @Query("UPDATE exam_sessions SET status = :status, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
    Mono<Integer> updateStatus(Long id, String status);

    /**
     * Complete session with score
     */
    @Modifying
    @Query("""
        UPDATE exam_sessions 
        SET status = 'COMPLETED', 
            score = :score, 
            total_points = :totalPoints,
            percentage = :percentage, 
            passed = :passed, 
            submitted_at = :submittedAt,
            end_time = :endTime,
            updated_at = CURRENT_TIMESTAMP 
        WHERE id = :id
    """)
    Mono<Integer> completeSession(Long id, BigDecimal score, BigDecimal totalPoints, 
                                   BigDecimal percentage, Boolean passed, 
                                   LocalDateTime submittedAt, LocalDateTime endTime);

    /**
     * Find timed out sessions
     */
    @Query("SELECT * FROM exam_sessions WHERE status = 'IN_PROGRESS' AND start_time < :cutoffTime")
    Flux<ExamSession> findTimedOutSessions(LocalDateTime cutoffTime);

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
