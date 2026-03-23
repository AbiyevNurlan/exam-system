package com.examportal.exam.repository;

import com.examportal.common.domain.entity.Question;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Question Repository
 * Reactive repository for Question entity operations.
 */
@Repository
public interface QuestionRepository extends R2dbcRepository<Question, Long> {

    /**
     * Find all questions for an exam ordered by their index
     */
    Flux<Question> findByExamIdOrderByOrderIndex(Long examId);

    /**
     * Find all active questions for an exam ordered by their index
     */
    @Query("SELECT * FROM questions WHERE exam_id = :examId AND active = true ORDER BY order_index ASC")
    Flux<Question> findActiveByExamIdOrderByOrderIndex(Long examId);

    /**
     * Count questions for an exam
     */
    Mono<Long> countByExamId(Long examId);

    /**
     * Count active questions for an exam
     */
    @Query("SELECT COUNT(*) FROM questions WHERE exam_id = :examId AND active = true")
    Mono<Long> countActiveByExamId(Long examId);

    /**
     * Delete all questions for an exam
     */
    Mono<Void> deleteByExamId(Long examId);

    /**
     * Soft delete all questions for an exam
     */
    @Modifying
    @Query("UPDATE questions SET active = false, updated_at = CURRENT_TIMESTAMP WHERE exam_id = :examId")
    Mono<Integer> softDeleteByExamId(Long examId);

    /**
     * Find question by ID and exam ID
     */
    Mono<Question> findByIdAndExamId(Long id, Long examId);

    /**
     * Check if a question exists for an exam
     */
    Mono<Boolean> existsByIdAndExamId(Long id, Long examId);

    /**
     * Get maximum order index for an exam
     */
    @Query("SELECT COALESCE(MAX(order_index), 0) FROM questions WHERE exam_id = :examId")
    Mono<Integer> getMaxOrderIndex(Long examId);

    /**
     * Reorder questions after deletion
     */
    @Modifying
    @Query("UPDATE questions SET order_index = order_index - 1 WHERE exam_id = :examId AND order_index > :deletedIndex")
    Mono<Integer> reorderAfterDelete(Long examId, Integer deletedIndex);
}
