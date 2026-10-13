package com.examportal.exam.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Session Fraud Event Repository
 * Events a participant's browser reported during a session (session_fraud_events). Write only:
 * nothing reads them back to a participant.
 */
@Repository
@RequiredArgsConstructor
public class SessionFraudEventRepository {

    private final DatabaseClient databaseClient;

    /**
     * Store one event while the session is IN_PROGRESS and has fewer than maxEvents; emits how many
     * rows were written (0 or 1). Run it inside a transaction: the advisory lock makes the writes of
     * one session take turns until commit, so parallel requests cannot pass the limit.
     */
    public Mono<Long> insertWhileBelowLimit(Long sessionPk, String type, LocalDateTime occurredAt,
                                            String details, int maxEvents) {
        DatabaseClient.GenericExecuteSpec insert = databaseClient.sql("""
                        INSERT INTO session_fraud_events (session_id, type, occurred_at, details, created_at)
                        SELECT s.id, :type, :occurredAt, CAST(:details AS VARCHAR), LOCALTIMESTAMP
                        FROM exam_sessions s
                        WHERE s.id = :sessionPk AND s.status = 'IN_PROGRESS'
                          AND (SELECT count(*) FROM session_fraud_events e WHERE e.session_id = s.id) < :maxEvents
                        """)
                .bind("sessionPk", sessionPk)
                .bind("type", type)
                .bind("occurredAt", occurredAt)
                .bind("maxEvents", maxEvents);
        insert = details != null ? insert.bind("details", details) : insert.bindNull("details", String.class);

        return databaseClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))")
                .bind("lockKey", "fraud-events:" + sessionPk)
                .then()
                .then(insert.fetch().rowsUpdated());
    }
}