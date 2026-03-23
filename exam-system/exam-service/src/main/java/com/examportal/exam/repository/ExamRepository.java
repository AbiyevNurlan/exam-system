package com.examportal.exam.repository;

import com.examportal.common.domain.entity.Exam;
import com.examportal.common.domain.entity.ExamStatus;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Exam Repository
 * Reactive repository for Exam entity operations.
 */
@Repository
public interface ExamRepository extends R2dbcRepository<Exam, Long> {

    /**
     * Find all exams created by a specific user
     */
    Flux<Exam> findByCreatedBy(Long userId);

    /**
     * Find all exams created by a user with specific status
     */
    Flux<Exam> findByCreatedByAndStatus(Long userId, ExamStatus status);

    /**
     * Find all exams with a specific status
     */
    Flux<Exam> findByStatus(ExamStatus status);

    /**
     * Find all active exams within a time window
     */
    @Query("SELECT * FROM exams WHERE status = 'ACTIVE' AND start_time <= :now AND end_time >= :now AND active = true")
    Flux<Exam> findActiveExams(LocalDateTime now);

    /**
     * Find exams available for a user (SCHEDULED or ACTIVE)
     */
    @Query("SELECT * FROM exams WHERE status IN ('SCHEDULED', 'ACTIVE') AND active = true ORDER BY start_time ASC")
    Flux<Exam> findAvailableExams();

    /**
     * Check if an exam exists and belongs to a user
     */
    Mono<Boolean> existsByIdAndCreatedBy(Long id, Long userId);

    /**
     * Check if exam title exists for a user
     */
    Mono<Boolean> existsByTitleAndCreatedBy(String title, Long userId);

    /**
     * Find overlapping exams for scheduling conflict check
     */
    @Query("""
        SELECT * FROM exams 
        WHERE created_by = :userId 
        AND status IN ('SCHEDULED', 'ACTIVE') 
        AND active = true
        AND id != COALESCE(:excludeId, -1)
        AND (
            (start_time <= :startTime AND end_time > :startTime) OR
            (start_time < :endTime AND end_time >= :endTime) OR
            (start_time >= :startTime AND end_time <= :endTime)
        )
    """)
    Flux<Exam> findOverlappingExams(Long userId, LocalDateTime startTime, LocalDateTime endTime, Long excludeId);

    /**
     * Update exam status
     */
    @Modifying
    @Query("UPDATE exams SET status = :status, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
    Mono<Integer> updateStatus(Long id, String status);

    /**
     * Update total questions count
     */
    @Modifying
    @Query("UPDATE exams SET total_questions = :count, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
    Mono<Integer> updateTotalQuestions(Long id, Integer count);

    /**
     * Soft delete an exam
     */
    @Modifying
    @Query("UPDATE exams SET active = false, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
    Mono<Integer> softDelete(Long id);

    /**
     * Find exams that should be activated (start time reached)
     */
    @Query("SELECT * FROM exams WHERE status = 'SCHEDULED' AND start_time <= :now AND active = true")
    Flux<Exam> findExamsToActivate(LocalDateTime now);

    /**
     * Find exams that should be completed (end time reached)
     */
    @Query("SELECT * FROM exams WHERE status = 'ACTIVE' AND end_time <= :now AND active = true")
    Flux<Exam> findExamsToComplete(LocalDateTime now);
}
