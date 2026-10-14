package com.examportal.exam.repository;

import com.examportal.common.domain.entity.ExamAudience;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Exam Audience Repository
 * Who may access an exam is decided by ExamAccessPolicy; this only stores the audience.
 */
@Repository
public interface ExamAudienceRepository extends R2dbcRepository<ExamAudience, Long> {

    @Query("SELECT group_id FROM exam_audience WHERE exam_id = :examId ORDER BY group_id")
    Flux<Long> findGroupIdsByExamId(Long examId);

    /**
     * The audience groups of an exam that still exist (a deleted group gives access to nobody)
     */
    @Query("""
        SELECT a.group_id FROM exam_audience a
        JOIN user_groups g ON g.id = a.group_id AND g.active = true
        WHERE a.exam_id = :examId ORDER BY a.group_id
    """)
    Flux<Long> findActiveGroupIdsByExamId(Long examId);

    @Modifying
    @Query("DELETE FROM exam_audience WHERE exam_id = :examId")
    Mono<Integer> deleteByExamId(Long examId);

    @Modifying
    @Query("INSERT INTO exam_audience (exam_id, group_id, created_at) VALUES (:examId, :groupId, CURRENT_TIMESTAMP)")
    Mono<Integer> add(Long examId, Long groupId);
}