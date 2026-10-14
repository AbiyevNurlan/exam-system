package com.examportal.exam.repository;

import com.examportal.common.domain.dto.grading.PendingCountResponse;
import com.examportal.common.domain.dto.grading.PendingExamResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Grading Overview Repository
 * What waits for a grader, over all exams: a completed session with grading status PENDING and its
 * answers that still need review (the same definition as the grading queue and the platform figures).
 * Each answer is one aggregate statement, whatever the number of exams and sessions.
 */
@Repository
@RequiredArgsConstructor
public class GradingOverviewRepository {

    private final DatabaseClient databaseClient;

    public record PendingPage(List<PendingExamResponse> rows, long total) {
    }

    private record PendingRow(PendingExamResponse exam, long total) {
    }

    // Pending sessions per exam (not deleted), with their open answers; ownerId null = every exam
    private static final String PENDING = """
            SELECT s.exam_id,
                   COUNT(*) AS session_count,
                   COALESCE(SUM(a.answers), 0) AS answer_count,
                   MIN(s.submitted_at) AS oldest_pending_at
            FROM exam_sessions s
            JOIN exams e ON e.id = s.exam_id AND e.active = true%s
            CROSS JOIN LATERAL (SELECT COUNT(*) AS answers FROM user_answers x
                                WHERE x.session_id = s.id AND x.needs_manual_review) a
            WHERE s.status = 'COMPLETED' AND s.grading_status = 'PENDING'
            GROUP BY s.exam_id
            """;

    /**
     * One page of the exams with pending sessions, oldest waiting first; the total comes with the rows
     */
    public Mono<PendingPage> findPendingExams(UUID ownerId, int limit, long offset) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        WITH pending AS (%s)
                        SELECT p.exam_id, e.title, p.session_count, p.answer_count, p.oldest_pending_at,
                               COUNT(*) OVER () AS total
                        FROM pending p
                        JOIN exams e ON e.id = p.exam_id
                        ORDER BY p.oldest_pending_at ASC NULLS LAST, p.exam_id ASC
                        LIMIT :limit OFFSET :offset
                        """.formatted(PENDING.formatted(ownerCondition(ownerId))))
                .bind("limit", limit)
                .bind("offset", offset);
        if (ownerId != null) {
            spec = spec.bind("ownerId", ownerId);
        }
        return spec.map(row -> new PendingRow(
                        PendingExamResponse.builder()
                                .examId(row.get("exam_id", Long.class))
                                .title(row.get("title", String.class))
                                .pendingSessionCount(row.get("session_count", Long.class))
                                .pendingAnswerCount(row.get("answer_count", Number.class).longValue())
                                .oldestPendingAt(row.get("oldest_pending_at", LocalDateTime.class))
                                .build(),
                        row.get("total", Long.class)))
                .all()
                .collectList()
                .flatMap(rows -> rows.isEmpty() && offset > 0
                        // Past the last page the window has no row to carry the total: count separately
                        ? countPendingExams(ownerId).map(total -> new PendingPage(List.of(), total))
                        : Mono.just(new PendingPage(rows.stream().map(PendingRow::exam).toList(),
                                rows.isEmpty() ? 0 : rows.get(0).total())));
    }

    /**
     * Pending sessions and their open answers over all exams (of the owner)
     */
    public Mono<PendingCountResponse> countPending(UUID ownerId) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        SELECT COALESCE(SUM(p.session_count), 0) AS sessions, COALESCE(SUM(p.answer_count), 0) AS answers
                        FROM (%s) p
                        """.formatted(PENDING.formatted(ownerCondition(ownerId))));
        if (ownerId != null) {
            spec = spec.bind("ownerId", ownerId);
        }
        return spec.map(row -> PendingCountResponse.builder()
                        .sessions(row.get("sessions", Number.class).longValue())
                        .answers(row.get("answers", Number.class).longValue())
                        .build())
                .one();
    }

    private Mono<Long> countPendingExams(UUID ownerId) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("SELECT COUNT(*) FROM (%s) p"
                .formatted(PENDING.formatted(ownerCondition(ownerId))));
        if (ownerId != null) {
            spec = spec.bind("ownerId", ownerId);
        }
        return spec.map(row -> row.get(0, Long.class)).one();
    }

    private String ownerCondition(UUID ownerId) {
        return ownerId != null ? " AND e.created_by = :ownerId" : "";
    }
}
