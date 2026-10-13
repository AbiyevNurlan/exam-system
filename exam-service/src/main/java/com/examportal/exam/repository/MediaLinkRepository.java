package com.examportal.exam.repository;

import com.examportal.common.domain.entity.MediaKind;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Media Link Repository
 * The tables that connect media to questions and options (question_media, option_media) and the
 * per-session play counter (media_play_counts). They have composite keys, so they are handled with
 * plain SQL instead of entity repositories.
 */
@Repository
@RequiredArgsConstructor
public class MediaLinkRepository {

    public static final String TARGET_QUESTION = "QUESTION";
    public static final String TARGET_OPTION = "OPTION";

    // Exams that participants can already see: everything except a draft
    private static final String PUBLISHED = "e.active = true AND e.status <> 'DRAFT'";

    private final DatabaseClient databaseClient;

    /**
     * One media attached to a question or an option of an exam.
     * storageKey is for signing URLs only and must never be put into a response.
     */
    public record AttachedMedia(String target, Long targetId, UUID mediaId, MediaKind kind, String altText,
                                Integer durationSeconds, int position, Integer maxPlays, String storageKey) {
    }

    /**
     * Attach a media to a question, but only while the media is ACTIVE; emits how many rows were
     * written (0 = it is no longer ACTIVE). The row lock (FOR KEY SHARE) and the orphan cleanup's
     * FOR UPDATE on the same row exclude each other, so a media can never be attached while it is
     * being removed, nor removed while it is being attached.
     */
    public Mono<Long> attachToQuestion(Long questionId, UUID mediaId, int position, Integer maxPlays) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        INSERT INTO question_media (question_id, media_id, position, max_plays)
                        SELECT :questionId, m.id, CAST(:position AS SMALLINT), CAST(:maxPlays AS SMALLINT)
                        FROM media_assets m
                        WHERE m.id = :mediaId AND m.status = 'ACTIVE'
                        FOR KEY SHARE OF m
                        """)
                .bind("questionId", questionId)
                .bind("mediaId", mediaId)
                .bind("position", position);
        spec = maxPlays != null ? spec.bind("maxPlays", maxPlays) : spec.bindNull("maxPlays", Integer.class);
        return spec.fetch().rowsUpdated();
    }

    /**
     * Attach an image to an option; same guard as attachToQuestion
     */
    public Mono<Long> attachToOption(Long optionId, UUID mediaId) {
        return databaseClient.sql("""
                        INSERT INTO option_media (option_id, media_id)
                        SELECT :optionId, m.id
                        FROM media_assets m
                        WHERE m.id = :mediaId AND m.status = 'ACTIVE'
                        FOR KEY SHARE OF m
                        """)
                .bind("optionId", optionId)
                .bind("mediaId", mediaId)
                .fetch()
                .rowsUpdated();
    }

    /**
     * Every ACTIVE media of an exam's questions and options, in display order
     */
    public Flux<AttachedMedia> findByExamId(Long examId) {
        return databaseClient.sql("""
                        SELECT 'QUESTION' AS target, qm.question_id AS target_id, qm.position::int AS position, qm.max_plays::int AS max_plays,
                               m.id, m.kind, m.alt_text, m.duration_seconds, m.storage_key
                        FROM question_media qm
                        JOIN questions q ON q.id = qm.question_id
                        JOIN media_assets m ON m.id = qm.media_id
                        WHERE q.exam_id = :examId AND m.status = 'ACTIVE'
                        UNION ALL
                        SELECT 'OPTION', om.option_id, 0, NULL::int,
                               m.id, m.kind, m.alt_text, m.duration_seconds, m.storage_key
                        FROM option_media om
                        JOIN question_options o ON o.id = om.option_id
                        JOIN questions q ON q.id = o.question_id
                        JOIN media_assets m ON m.id = om.media_id
                        WHERE q.exam_id = :examId AND m.status = 'ACTIVE'
                        ORDER BY 1, 2, 3
                        """)
                .bind("examId", examId)
                .map(row -> new AttachedMedia(
                        row.get("target", String.class),
                        row.get("target_id", Long.class),
                        row.get("id", UUID.class),
                        MediaKind.valueOf(row.get("kind", String.class)),
                        row.get("alt_text", String.class),
                        row.get("duration_seconds", Integer.class),
                        intOrZero(row.get("position", Integer.class)),
                        row.get("max_plays", Integer.class),
                        row.get("storage_key", String.class)))
                .all();
    }

    /**
     * The media as it is attached inside one exam; empty when it is not part of that exam (or not
     * ACTIVE). Attached more than once, the strictest play limit applies.
     */
    public Mono<AttachedMedia> findInExam(Long examId, UUID mediaId) {
        return databaseClient.sql("""
                        SELECT m.id, m.kind, m.alt_text, m.duration_seconds, m.storage_key,
                               (SELECT min(qm.max_plays)::int FROM question_media qm
                                JOIN questions q ON q.id = qm.question_id
                                WHERE q.exam_id = :examId AND qm.media_id = m.id) AS max_plays
                        FROM media_assets m
                        WHERE m.id = :mediaId AND m.status = 'ACTIVE'
                          AND (EXISTS (SELECT 1 FROM question_media qm
                                       JOIN questions q ON q.id = qm.question_id
                                       WHERE q.exam_id = :examId AND qm.media_id = m.id)
                               OR EXISTS (SELECT 1 FROM option_media om
                                          JOIN question_options o ON o.id = om.option_id
                                          JOIN questions q ON q.id = o.question_id
                                          WHERE q.exam_id = :examId AND om.media_id = m.id))
                        """)
                .bind("examId", examId)
                .bind("mediaId", mediaId)
                .map(row -> new AttachedMedia(null, null,
                        row.get("id", UUID.class),
                        MediaKind.valueOf(row.get("kind", String.class)),
                        row.get("alt_text", String.class),
                        row.get("duration_seconds", Integer.class),
                        0,
                        row.get("max_plays", Integer.class),
                        row.get("storage_key", String.class)))
                .one();
    }

    /**
     * Whether the media is used by an exam that is no longer a draft
     */
    public Mono<Boolean> isAttachedToPublishedExam(UUID mediaId) {
        return databaseClient.sql("""
                        SELECT EXISTS (SELECT 1 FROM question_media qm
                                       JOIN questions q ON q.id = qm.question_id
                                       JOIN exams e ON e.id = q.exam_id
                                       WHERE qm.media_id = :mediaId AND %s)
                            OR EXISTS (SELECT 1 FROM option_media om
                                       JOIN question_options o ON o.id = om.option_id
                                       JOIN questions q ON q.id = o.question_id
                                       JOIN exams e ON e.id = q.exam_id
                                       WHERE om.media_id = :mediaId AND %s)
                        """.formatted(PUBLISHED, PUBLISHED))
                .bind("mediaId", mediaId)
                .map(row -> Boolean.TRUE.equals(row.get(0, Boolean.class)))
                .one();
    }

    /**
     * Remove the media from every question and option (drafts only: the caller has refused published exams)
     */
    public Mono<Void> detachEverywhere(UUID mediaId) {
        return databaseClient.sql("DELETE FROM question_media WHERE media_id = :mediaId")
                .bind("mediaId", mediaId)
                .then()
                .then(databaseClient.sql("DELETE FROM option_media WHERE media_id = :mediaId")
                        .bind("mediaId", mediaId)
                        .then());
    }

    /**
     * Count one play, but only while the session is below the limit: ONE atomic statement, so
     * parallel requests cannot pass it. Emits the new count, or nothing when the plays are used up.
     */
    public Mono<Integer> registerPlay(String sessionId, UUID mediaId, int maxPlays) {
        return databaseClient.sql("""
                        INSERT INTO media_play_counts (session_id, media_id, plays)
                        VALUES (:sessionId, :mediaId, 1)
                        ON CONFLICT (session_id, media_id) DO UPDATE
                            SET plays = media_play_counts.plays + 1
                            WHERE media_play_counts.plays < :maxPlays
                        RETURNING plays
                        """)
                .bind("sessionId", sessionId)
                .bind("mediaId", mediaId)
                .bind("maxPlays", maxPlays)
                .map(row -> row.get("plays", Integer.class))
                .one();
    }

    private static int intOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
