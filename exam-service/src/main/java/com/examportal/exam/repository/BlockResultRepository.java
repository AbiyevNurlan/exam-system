package com.examportal.exam.repository;

import com.examportal.common.domain.dto.BlockResultResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The result of a session per block (B12a), in one statement: the questions of each block, the points they are
 * worth and what the session earned on them (user_answers.points_earned, so a manual grade counts once given).
 * An exam with attempts cannot change its questions (B9), so the blocks are the ones the session was taken with.
 */
@Repository
@RequiredArgsConstructor
public class BlockResultRepository {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final DatabaseClient databaseClient;

    /**
     * Every block of the exam in order, with showResultToParticipant set; the caller decides who sees which
     */
    public Flux<BlockResultResponse> forSession(Long examId, Long sessionDbId) {
        return databaseClient.sql("""
                        SELECT b.id, b.position::int AS position, b.title, b.show_result_to_participant,
                               COUNT(q.id) AS total_questions,
                               COUNT(a.id) AS answered,
                               COUNT(a.id) FILTER (WHERE a.is_correct) AS correct,
                               COALESCE(SUM(q.points), 0) AS max_score,
                               COALESCE(SUM(a.points_earned), 0) AS score
                        FROM exam_blocks b
                        LEFT JOIN questions q ON q.block_id = b.id AND q.active = true
                        LEFT JOIN user_answers a ON a.question_id = q.id AND a.session_id = :sessionId
                        WHERE b.exam_id = :examId
                        GROUP BY b.id, b.position, b.title, b.show_result_to_participant
                        ORDER BY b.position
                        """)
                .bind("examId", examId)
                .bind("sessionId", sessionDbId)
                .map(row -> {
                    BigDecimal max = row.get("max_score", BigDecimal.class);
                    BigDecimal score = row.get("score", BigDecimal.class);
                    return BlockResultResponse.builder()
                            .blockId(row.get("id", Long.class))
                            .position(row.get("position", Integer.class))
                            .title(row.get("title", String.class))
                            .showResultToParticipant(Boolean.TRUE.equals(row.get("show_result_to_participant", Boolean.class)))
                            .totalQuestions(row.get("total_questions", Long.class).intValue())
                            .answeredQuestions(row.get("answered", Long.class).intValue())
                            .correctAnswers(row.get("correct", Long.class).intValue())
                            .maxScore(max)
                            .score(score)
                            .percentage(max != null && max.signum() > 0
                                    ? score.multiply(HUNDRED).divide(max, 2, RoundingMode.HALF_UP) : null)
                            .build();
                })
                .all();
    }
}
