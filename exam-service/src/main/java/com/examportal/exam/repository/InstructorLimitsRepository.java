package com.examportal.exam.repository;

import com.examportal.common.domain.dto.instructor.InstructorDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.UUID;

/**
 * B13: the limits a teacher has (instructor_limits, exam V19) and what a teacher owns, each one statement on the
 * owner indexes (user_groups owner, idx_exams_created_by, idx_exams_time_range, idx_media_assets_owner_status).
 */
@Repository
@RequiredArgsConstructor
public class InstructorLimitsRepository {

    private final DatabaseClient databaseClient;

    /** The own limits of a teacher; a null field is the system default */
    public record Stored(Integer maxGroups, Integer maxMembers, Integer maxExams, Long maxMediaBytes) {
    }

    /** What a teacher owns: groups, distinct members, pending invites of the own groups, exams and media bytes */
    public record Usage(long groups, long members, long invites, long exams, long mediaBytes) {
    }

    // Figures of the owners in "o(id)"; fixed text only
    private static final String FIGURES = """
            SELECT o.id,
                   (SELECT COUNT(*) FROM user_groups g WHERE g.owner_id = o.id AND g.active = true) AS groups,
                   (SELECT COUNT(DISTINCT m.user_id) FROM user_groups g JOIN user_group_members m ON m.group_id = g.id
                    WHERE g.owner_id = o.id AND g.active = true) AS members,
                   (SELECT COUNT(*) FROM user_groups g JOIN pending_group_invites i ON i.group_id = g.id
                    WHERE g.owner_id = o.id AND g.active = true) AS invites,
                   (SELECT COUNT(*) FROM exams e WHERE e.created_by = o.id AND e.active = true) AS exams,
                   (SELECT COUNT(*) FROM exams e WHERE e.created_by = o.id AND e.active = true
                    AND e.status IN ('SCHEDULED', 'ACTIVE')) AS live_exams,
                   (SELECT COALESCE(SUM(a.size_bytes), 0) FROM media_assets a
                    WHERE a.owner_id = o.id AND a.status IN ('PENDING', 'ACTIVE')) AS media_bytes
            FROM %s
            """;

    public Mono<Stored> find(UUID instructorId) {
        return databaseClient.sql("""
                        SELECT max_groups, max_members, max_exams, max_media_bytes FROM instructor_limits
                        WHERE instructor_id = :id
                        """)
                .bind("id", instructorId)
                .map(row -> new Stored(row.get("max_groups", Integer.class), row.get("max_members", Integer.class),
                        row.get("max_exams", Integer.class), row.get("max_media_bytes", Long.class)))
                .one();
    }

    /**
     * The own limits of a teacher (null = the system default), who set them and when
     */
    public Mono<Void> save(UUID instructorId, Stored limits, UUID adminId) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        INSERT INTO instructor_limits (instructor_id, max_groups, max_members, max_exams, max_media_bytes,
                                                       updated_by, updated_at)
                        VALUES (:id, :groups, :members, :exams, :media, :admin, now() AT TIME ZONE 'UTC')
                        ON CONFLICT (instructor_id) DO UPDATE SET max_groups = EXCLUDED.max_groups,
                            max_members = EXCLUDED.max_members, max_exams = EXCLUDED.max_exams,
                            max_media_bytes = EXCLUDED.max_media_bytes, updated_by = EXCLUDED.updated_by,
                            updated_at = EXCLUDED.updated_at
                        """)
                .bind("id", instructorId)
                .bind("admin", adminId);
        spec = limits.maxGroups() != null ? spec.bind("groups", limits.maxGroups()) : spec.bindNull("groups", Integer.class);
        spec = limits.maxMembers() != null ? spec.bind("members", limits.maxMembers()) : spec.bindNull("members", Integer.class);
        spec = limits.maxExams() != null ? spec.bind("exams", limits.maxExams()) : spec.bindNull("exams", Integer.class);
        spec = limits.maxMediaBytes() != null ? spec.bind("media", limits.maxMediaBytes()) : spec.bindNull("media", Long.class);
        return spec.then();
    }

    public Mono<Usage> usage(UUID instructorId) {
        return databaseClient.sql(FIGURES.formatted("(SELECT CAST(:id AS uuid) AS id) o"))
                .bind("id", instructorId)
                .map(row -> new Usage(number(row.get("groups")), number(row.get("members")), number(row.get("invites")),
                        number(row.get("exams")), number(row.get("media_bytes"))))
                .one();
    }

    /**
     * The figures of many teachers in one statement (the administrator's list)
     */
    public Flux<InstructorDtos.Figures> figures(Collection<UUID> instructorIds) {
        return databaseClient.sql(FIGURES.formatted("unnest(CAST(:ids AS uuid[])) AS o(id)"))
                .bind("ids", instructorIds.toArray(new UUID[0]))
                .map(row -> InstructorDtos.Figures.builder()
                        .id(row.get("id", UUID.class))
                        .groupCount(number(row.get("groups")))
                        .memberCount(number(row.get("members")))
                        .examCount(number(row.get("exams")))
                        .activeExamCount(number(row.get("live_exams")))
                        .build())
                .all();
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }
}
