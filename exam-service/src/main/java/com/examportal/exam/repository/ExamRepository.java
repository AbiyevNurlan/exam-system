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
import java.util.UUID;

/**
 * Exam Repository
 * Reactive repository for Exam entity operations.
 */
@Repository
public interface ExamRepository extends R2dbcRepository<Exam, Long> {

    /**
     * Find all exams created by a specific user
     */
    Flux<Exam> findByCreatedBy(UUID userId);

    /**
     * Find all exams created by a user with specific status
     */
    Flux<Exam> findByCreatedByAndStatus(UUID userId, ExamStatus status);

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
     * The access rule as a SQL condition on "exams e". Used only through ExamAccessPolicy, which holds
     * the same rule for a single exam: ADMIN, the owning INSTRUCTOR, every PUBLIC exam, and GROUP exams
     * with at least one active audience group the user is a member of.
     */
    String ACCESS_CONDITION = """
        (:admin OR (:instructor AND e.created_by = :userId) OR e.access_type = 'PUBLIC' OR EXISTS (
            SELECT 1 FROM exam_audience a
            JOIN user_groups g ON g.id = a.group_id AND g.active = true
            JOIN user_group_members m ON m.group_id = a.group_id
            WHERE a.exam_id = e.id AND m.user_id = :userId))
        """;

    /**
     * SCHEDULED or ACTIVE exams the user may access and can still start or resume: an attempt is left
     * (every session counts) or an active session still has time.
     */
    String AVAILABLE_CONDITION = """
        e.status IN ('SCHEDULED', 'ACTIVE') AND e.active = true
        AND """ + ACCESS_CONDITION + """
        AND ((SELECT COUNT(*) FROM exam_sessions s WHERE s.exam_id = e.id AND s.user_id = :userId)
                < COALESCE(e.max_attempts, 1)
             OR EXISTS (SELECT 1 FROM exam_sessions s WHERE s.exam_id = e.id AND s.user_id = :userId
                        AND s.status = 'IN_PROGRESS' AND s.end_time > :now))
        """;

    /**
     * One page of the exams available to a user. Call through ExamAccessPolicy.
     */
    @Query("SELECT e.* FROM exams e WHERE " + AVAILABLE_CONDITION
            + " ORDER BY e.start_time ASC, e.id ASC LIMIT :limit OFFSET :offset")
    Flux<Exam> findAvailableExams(UUID userId, boolean admin, boolean instructor, LocalDateTime now,
                                  int limit, long offset);

    /**
     * Number of exams available to a user. Call through ExamAccessPolicy.
     */
    @Query("SELECT COUNT(*) FROM exams e WHERE " + AVAILABLE_CONDITION)
    Mono<Long> countAvailableExams(UUID userId, boolean admin, boolean instructor, LocalDateTime now);

    /**
     * Whether the user is a member of at least one active audience group of the exam.
     * Call through ExamAccessPolicy.
     */
    @Query("""
        SELECT EXISTS (
            SELECT 1 FROM exam_audience a
            JOIN user_groups g ON g.id = a.group_id AND g.active = true
            JOIN user_group_members m ON m.group_id = a.group_id
            WHERE a.exam_id = :examId AND m.user_id = :userId)
    """)
    Mono<Boolean> isAudienceMember(Long examId, UUID userId);

    /**
     * Check if an exam exists and belongs to a user
     */
    Mono<Boolean> existsByIdAndCreatedBy(Long id, UUID userId);

    /**
     * Check if exam title exists for a user
     */
    Mono<Boolean> existsByTitleAndCreatedBy(String title, UUID userId);

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
     * Set who may take the exam (PUBLIC or GROUP)
     */
    @Modifying
    @Query("UPDATE exams SET access_type = :accessType, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
    Mono<Integer> updateAccessType(Long id, String accessType);

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
