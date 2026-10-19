package com.examportal.exam.history;

import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * exam_published_snapshots (B12a, V17): version 1, 2, ... of an exam, one per publication, append-only.
 * The next version number is taken inside the publishing transaction, which holds the exam row
 * (the status UPDATE before it), so two publications of one exam cannot take the same number.
 */
@Repository
@RequiredArgsConstructor
public class ExamSnapshotRepository {

    private final DatabaseClient databaseClient;

    public record Snapshot(Long examId, int versionNo, String snapshotJson, String sha256, UUID actorUserId,
                           Integer actorCode, String actorName, LocalDateTime createdAt) {
    }

    /**
     * Stores the next version; emits its number
     */
    public Mono<Integer> append(Long examId, String canonicalJson, String sha256, ChangeContext context) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        INSERT INTO exam_published_snapshots (exam_id, version_no, snapshot, sha256, actor_user_id, actor_code, actor_name)
                        SELECT :examId, COALESCE(MAX(version_no), 0) + 1, :snapshot, :sha256, :actorId, :actorCode, :actorName
                        FROM exam_published_snapshots WHERE exam_id = :examId
                        RETURNING version_no
                        """)
                .bind("examId", examId)
                .bind("snapshot", Json.of(canonicalJson))
                .bind("sha256", sha256)
                .bind("actorId", context.actorId());
        spec = context.actorCode() != null ? spec.bind("actorCode", context.actorCode()) : spec.bindNull("actorCode", Integer.class);
        spec = context.actorName() != null ? spec.bind("actorName", context.actorName()) : spec.bindNull("actorName", String.class);
        return spec.map(row -> row.get("version_no", Integer.class)).one();
    }

    /**
     * Every version of an exam, newest first, without the snapshot itself
     */
    public Flux<Snapshot> list(Long examId) {
        return databaseClient.sql("""
                        SELECT exam_id, version_no, NULL::text AS snapshot, sha256, actor_user_id, actor_code, actor_name, created_at
                        FROM exam_published_snapshots WHERE exam_id = :examId ORDER BY version_no DESC
                        """)
                .bind("examId", examId)
                .map(row -> toSnapshot(row))
                .all();
    }

    public Mono<Snapshot> find(Long examId, int versionNo) {
        return databaseClient.sql("""
                        SELECT exam_id, version_no, snapshot::text AS snapshot, sha256, actor_user_id, actor_code, actor_name, created_at
                        FROM exam_published_snapshots WHERE exam_id = :examId AND version_no = :versionNo
                        """)
                .bind("examId", examId)
                .bind("versionNo", versionNo)
                .map(row -> toSnapshot(row))
                .one();
    }

    private static Snapshot toSnapshot(io.r2dbc.spi.Readable row) {
        return new Snapshot(row.get("exam_id", Long.class), row.get("version_no", Integer.class),
                row.get("snapshot", String.class), row.get("sha256", String.class), row.get("actor_user_id", UUID.class),
                row.get("actor_code", Integer.class), row.get("actor_name", String.class),
                row.get("created_at", LocalDateTime.class));
    }
}
