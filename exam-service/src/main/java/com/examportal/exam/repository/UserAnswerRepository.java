package com.examportal.exam.repository;

import com.examportal.common.domain.entity.UserAnswer;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * User Answer Repository
 * Reactive repository for UserAnswer entity operations.
 */
@Repository
public interface UserAnswerRepository extends R2dbcRepository<UserAnswer, Long> {

    /**
     * Find all answers for a session
     */
    Flux<UserAnswer> findBySessionId(Long sessionId);

    /**
     * Find answer by session and question
     */
    Mono<UserAnswer> findBySessionIdAndQuestionId(Long sessionId, Long questionId);

    /**
     * Check if answer exists for question in session
     */
    Mono<Boolean> existsBySessionIdAndQuestionId(Long sessionId, Long questionId);

    /**
     * Count answers for a session
     */
    Mono<Long> countBySessionId(Long sessionId);

    /**
     * Count answers that were graded at completion (answers to questions still active in the exam)
     */
    @Query("SELECT COUNT(*) FROM user_answers WHERE session_id = :sessionId AND is_correct IS NOT NULL")
    Mono<Long> countGradedBySessionId(Long sessionId);

    /**
     * Count correct answers for a session
     */
    @Query("SELECT COUNT(*) FROM user_answers WHERE session_id = :sessionId AND is_correct = true")
    Mono<Long> countCorrectBySessionId(Long sessionId);

    /**
     * Calculate total points earned for a session
     */
    @Query("SELECT COALESCE(SUM(points_earned), 0) FROM user_answers WHERE session_id = :sessionId")
    Mono<BigDecimal> sumPointsEarnedBySessionId(Long sessionId);

    /**
     * Insert the answer, or overwrite the existing one for the same (session, question).
     * A single statement, so concurrent submissions cannot create duplicates.
     */
    @Modifying
    @Query("""
        INSERT INTO user_answers (session_id, question_id, selected_option_ids, text_answer,
                                  needs_manual_review, answered_at, created_at, updated_at)
        VALUES (:sessionId, :questionId, :selectedOptionIds, :textAnswer, false, :now, :now, :now)
        ON CONFLICT (session_id, question_id) DO UPDATE
        SET selected_option_ids = EXCLUDED.selected_option_ids,
            text_answer = EXCLUDED.text_answer,
            needs_manual_review = EXCLUDED.needs_manual_review,
            answered_at = EXCLUDED.answered_at,
            updated_at = EXCLUDED.updated_at
    """)
    Mono<Integer> upsertAnswer(Long sessionId, Long questionId, Long[] selectedOptionIds,
                               String textAnswer, LocalDateTime now);

    /**
     * Store the grading outcome of one answer
     */
    @Modifying
    @Query("UPDATE user_answers SET is_correct = :isCorrect, points_earned = :pointsEarned WHERE id = :id")
    Mono<Integer> saveGrade(Long id, Boolean isCorrect, BigDecimal pointsEarned);

    /**
     * Delete all answers for a session
     */
    Mono<Void> deleteBySessionId(Long sessionId);
}
