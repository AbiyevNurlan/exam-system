package com.examportal.exam.repository;

import com.examportal.common.domain.entity.QuestionOption;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;

/**
 * Question Option Repository
 * Reactive repository for QuestionOption entity operations.
 */
@Repository
public interface QuestionOptionRepository extends R2dbcRepository<QuestionOption, Long> {

    /**
     * Find all options for a question
     */
    Flux<QuestionOption> findByQuestionId(Long questionId);

    /**
     * Find all options for a question ordered by their index
     */
    Flux<QuestionOption> findByQuestionIdOrderByOrderIndex(Long questionId);

    /**
     * Find all options for multiple questions
     */
    @Query("SELECT * FROM question_options WHERE question_id IN (:questionIds) ORDER BY question_id, order_index")
    Flux<QuestionOption> findByQuestionIdIn(Collection<Long> questionIds);

    /**
     * Delete all options for a question
     */
    Mono<Void> deleteByQuestionId(Long questionId);

    /**
     * Count options for a question
     */
    Mono<Long> countByQuestionId(Long questionId);

    /**
     * Find correct options for a question
     */
    @Query("SELECT * FROM question_options WHERE question_id = :questionId AND is_correct = true")
    Flux<QuestionOption> findCorrectOptionsByQuestionId(Long questionId);

    /**
     * Find correct options for multiple questions
     */
    @Query("SELECT * FROM question_options WHERE question_id IN (:questionIds) AND is_correct = true")
    Flux<QuestionOption> findCorrectOptionsByQuestionIds(Collection<Long> questionIds);

    /**
     * Count correct options for a question
     */
    @Query("SELECT COUNT(*) FROM question_options WHERE question_id = :questionId AND is_correct = true")
    Mono<Long> countCorrectByQuestionId(Long questionId);

    /**
     * Find options by IDs
     */
    @Query("SELECT * FROM question_options WHERE id IN (:ids)")
    Flux<QuestionOption> findByIdIn(Collection<Long> ids);
}
