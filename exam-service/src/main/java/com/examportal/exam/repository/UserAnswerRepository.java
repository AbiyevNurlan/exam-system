package com.examportal.exam.repository;

import com.examportal.common.domain.entity.UserAnswer;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

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
     * Delete all answers for a session
     */
    Mono<Void> deleteBySessionId(Long sessionId);
}
