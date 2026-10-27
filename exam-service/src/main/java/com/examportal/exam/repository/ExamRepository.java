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
     * the same rule for a single exam: ADMIN every exam; an INSTRUCTOR only the own ones (B13: no PUBLIC
     * exam and no audience of another teacher); anybody else every PUBLIC exam and the GROUP exams with at
     * least one active audience group the user is a member of. B13b: never a PAID_OPEN exam (only ADMIN and its owner).
     */
    String ACCESS_CONDITION = """
        (:admin OR (:instructor AND e.created_by = :userId) OR (NOT :instructor AND e.access_mode = 'AUDIENCE' AND (e.access_type = 'PUBLIC' OR EXISTS (
            SELECT 1 FROM exam_audience a
            JOIN user_groups g ON g.id = a.group_id AND g.active = true
            JOIN user_group_members m ON m.group_id = a.group_id
            WHERE a.exam_id = e.id AND m.user_id = :userId))))
        """;

    /**
     * B13: the exam (not deleted) when the caller may manage it, the ownership in the SQL condition:
     * admin = every exam, otherwise only an exam created by owner. Empty for anything else, so another
     * owner's exam is the same 404 as a missing one. Call through ExamAccessPolicy.
     */
    @Query("SELECT * FROM exams e WHERE e.id = :id AND e.active IS DISTINCT FROM false AND (:admin OR e.created_by = :owner)")
    Mono<Exam> findManageableById(Long id, boolean admin, UUID owner);

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
     * Remove an exam for good, but only while it has no session: returns 1 when it was removed.
     * Its questions, options, media links and audience rows go with it in the same statement
     * (ON DELETE CASCADE); the media files themselves are not touched and are left to the orphan
     * cleanup. A session opened at the same moment makes the statement fail on the foreign key
     * of exam_sessions instead of removing an exam that now has an attempt.
     */
    @Modifying
    @Query("""
        DELETE FROM exams e
        WHERE e.id = :id AND NOT EXISTS (SELECT 1 FROM exam_sessions s WHERE s.exam_id = e.id)
    """)
    Mono<Integer> deleteIfNoSessions(Long id);

    /**
     * The exam row, locked until the end of the surrounding transaction, so two changes of the same
     * exam take turns. FOR NO KEY UPDATE: sessions can still be opened for it meanwhile.
     */
    @Query("SELECT * FROM exams WHERE id = :id AND active = true FOR NO KEY UPDATE")
    Mono<Exam> lockById(Long id);

    /**
     * Give the exam a new time window and status; a null maxAttempts keeps the stored one, and the
     * daily limit is only written when setMaxAttemptsPerDay is true (null then removes it).
     * Questions, sessions and results are not touched.
     */
    @Modifying
    @Query("""
        UPDATE exams
        SET start_time = :startTime,
            end_time = :endTime,
            status = :status,
            max_attempts = COALESCE(CAST(:maxAttempts AS INTEGER), max_attempts),
            max_attempts_per_day = CASE WHEN :setMaxAttemptsPerDay
                                        THEN CAST(:maxAttemptsPerDay AS SMALLINT)
                                        ELSE max_attempts_per_day END,
            updated_at = CURRENT_TIMESTAMP,
            version = COALESCE(version, 0) + 1
        WHERE id = :id AND active = true
    """)
    Mono<Integer> reschedule(Long id, LocalDateTime startTime, LocalDateTime endTime, String status,
                             Integer maxAttempts, boolean setMaxAttemptsPerDay, Integer maxAttemptsPerDay);

    /**
     * SCHEDULED to ACTIVE, but only while the exam is still SCHEDULED and due: returns 1 for the
     * caller that made the transition. The scheduler works from a list it read earlier, so the
     * condition is checked again here and a change made in between (reschedule, archive) wins.
     */
    @Modifying
    @Query("""
        UPDATE exams SET status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
        WHERE id = :id AND status = 'SCHEDULED' AND start_time <= :now AND active = true
    """)
    Mono<Integer> activateIfDue(Long id, LocalDateTime now);

    /**
     * ACTIVE to COMPLETED, but only while the exam is still ACTIVE and its end time has passed
     * (see activateIfDue): an exam that was just given a later end time is left alone.
     */
    @Modifying
    @Query("""
        UPDATE exams SET status = 'COMPLETED', updated_at = CURRENT_TIMESTAMP
        WHERE id = :id AND status = 'ACTIVE' AND end_time <= :now AND active = true
    """)
    Mono<Integer> completeIfEnded(Long id, LocalDateTime now);

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
