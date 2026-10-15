package com.examportal.exam.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * The title and the description of an exam, the only columns PATCH /api/exams/{id}/meta writes.
 * Nothing that decides a result (questions, options, points, answer key, order, duration, window,
 * attempts) is touched. The version is raised, so an edit that read the exam before fails its
 * optimistic lock instead of writing an old title back.
 */
@Repository
@RequiredArgsConstructor
public class ExamMetaRepository {

    private final DatabaseClient databaseClient;

    /**
     * setTitle / setDescription false keep the stored value; a null description removes it.
     * Emits the number of rows changed (0 when the exam is gone).
     */
    public Mono<Long> update(Long examId, boolean setTitle, String title, boolean setDescription, String description) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        UPDATE exams
                        SET title = CASE WHEN :setTitle THEN :title ELSE title END,
                            description = CASE WHEN :setDescription THEN :description ELSE description END,
                            updated_at = CURRENT_TIMESTAMP,
                            version = COALESCE(version, 0) + 1
                        WHERE id = :examId AND active = true
                        """)
                .bind("examId", examId)
                .bind("setTitle", setTitle)
                .bind("setDescription", setDescription);
        spec = title != null ? spec.bind("title", title) : spec.bindNull("title", String.class);
        spec = description != null ? spec.bind("description", description) : spec.bindNull("description", String.class);
        return spec.fetch().rowsUpdated();
    }
}
