package com.examportal.exam.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * The lock that keeps an exam's questions and the sessions taken with them consistent.
 *
 * An edit locks the exam row FOR UPDATE and only then asks whether a session exists, in a statement
 * of its own: under READ COMMITTED that statement takes a new snapshot after the lock was granted,
 * so it sees every session whose start committed while the edit waited. A start takes the same row
 * FOR SHARE (ExamAttemptRepository.openSessionIfAttemptsRemain), which waits for an edit in progress
 * and reads the questions only after it has committed. Either the edit sees the session and is
 * refused, or the session is created after the edit with the complete new set of questions.
 * Both methods must run inside the caller's transaction.
 */
@Repository
@RequiredArgsConstructor
public class ExamEditLock {

    private final DatabaseClient databaseClient;

    /**
     * Lock the exam row until the end of the surrounding transaction. Emits the exam id, or nothing
     * when the exam does not exist (or was deleted).
     */
    public Mono<Long> lockForEdit(Long examId) {
        return databaseClient.sql("SELECT id FROM exams WHERE id = :examId AND active = true FOR UPDATE")
                .bind("examId", examId)
                .map(row -> row.get("id", Long.class))
                .one();
    }

    /**
     * Whether any session (attempt) of the exam exists, in any status
     */
    public Mono<Boolean> hasSessions(Long examId) {
        return databaseClient.sql("SELECT EXISTS (SELECT 1 FROM exam_sessions WHERE exam_id = :examId) AS taken")
                .bind("examId", examId)
                .map(row -> Boolean.TRUE.equals(row.get("taken", Boolean.class)))
                .one();
    }
}
