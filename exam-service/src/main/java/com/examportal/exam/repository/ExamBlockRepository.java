package com.examportal.exam.repository;

import com.examportal.common.domain.entity.ExamBlock;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

/**
 * The blocks of an exam (B12a), for responses. Blocks are written by ExamStructureWriter only.
 */
@Repository
public interface ExamBlockRepository extends R2dbcRepository<ExamBlock, Long> {

    Flux<ExamBlock> findByExamIdOrderByPosition(Long examId);
}
