package com.examportal.exam.repository;

import com.examportal.common.domain.dto.participant.ParticipantCardDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Participant Card Repository (B11)
 * The exam-service part of the admin's participant card, each a single statement on an index of the user:
 * the groups (idx_group_members_user_id), a page of attempts (idx_sessions_user_start), whether an
 * instructor owns a group of the user, and the newest group/exam events (idx_group_audit_actor_created,
 * idx_group_audit_target_created). owner = null: everything (ADMIN); otherwise only the groups this
 * instructor owns and the exams this instructor created.
 */
@Repository
@RequiredArgsConstructor
public class ParticipantCardRepository {

    private final DatabaseClient databaseClient;

    /**
     * Is the user a member of an active group the instructor owns
     */
    public Mono<Boolean> isMemberOfGroupOwnedBy(UUID userId, UUID owner) {
        return databaseClient.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM user_group_members m
                            JOIN user_groups g ON g.id = m.group_id AND g.active = true
                            WHERE m.user_id = :userId AND g.owner_id = :owner
                        ) AS member
                        """)
                .bind("userId", userId)
                .bind("owner", owner)
                .map(row -> Boolean.TRUE.equals(row.get("member", Boolean.class)))
                .one();
    }

    public Mono<List<ParticipantCardDtos.Membership>> findGroups(UUID userId, UUID owner) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        SELECT g.id, g.name, m.source, m.created_at
                        FROM user_group_members m
                        JOIN user_groups g ON g.id = m.group_id AND g.active = true
                        WHERE m.user_id = :userId
                        """ + (owner == null ? "" : " AND g.owner_id = :owner") + """
                         ORDER BY btrim(g.name) COLLATE az_ci, g.id
                        """)
                .bind("userId", userId);
        if (owner != null) {
            spec = spec.bind("owner", owner);
        }
        return spec.map(row -> ParticipantCardDtos.Membership.builder()
                        .groupId(row.get("id", Long.class))
                        .groupName(row.get("name", String.class))
                        .source(row.get("source", String.class))
                        .addedAt(row.get("created_at", LocalDateTime.class))
                        .build())
                .all()
                .collectList();
    }

    public Mono<Long> countSessions(UUID userId, UUID owner) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("SELECT COUNT(*) FROM exam_sessions s"
                        + (owner == null ? "" : " JOIN exams e ON e.id = s.exam_id AND e.created_by = :owner")
                        + " WHERE s.user_id = :userId")
                .bind("userId", userId);
        if (owner != null) {
            spec = spec.bind("owner", owner);
        }
        return spec.map(row -> row.get(0, Long.class)).one();
    }

    /**
     * One page of the user's attempts, newest first
     */
    public Mono<List<ParticipantCardDtos.SessionRow>> findSessions(UUID userId, UUID owner, int page, int size) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        SELECT s.session_id, s.exam_id, e.title, s.status, s.grading_status, s.score, s.total_points,
                               s.percentage, s.passed, s.start_time, s.submitted_at
                        FROM exam_sessions s
                        JOIN exams e ON e.id = s.exam_id
                        WHERE s.user_id = :userId
                        """ + (owner == null ? "" : " AND e.created_by = :owner") + """
                         ORDER BY s.start_time DESC, s.id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .bind("userId", userId)
                .bind("limit", size)
                .bind("offset", (long) page * size);
        if (owner != null) {
            spec = spec.bind("owner", owner);
        }
        return spec.map(row -> ParticipantCardDtos.SessionRow.builder()
                        .sessionId(row.get("session_id", String.class))
                        .examId(row.get("exam_id", Long.class))
                        .examTitle(row.get("title", String.class))
                        .status(row.get("status", String.class))
                        .gradingStatus(row.get("grading_status", String.class))
                        .score(row.get("score", BigDecimal.class))
                        .totalPoints(row.get("total_points", BigDecimal.class))
                        .percentage(row.get("percentage", BigDecimal.class))
                        .passed(row.get("passed", Boolean.class))
                        .startedAt(row.get("start_time", LocalDateTime.class))
                        .submittedAt(row.get("submitted_at", LocalDateTime.class))
                        .build())
                .all()
                .collectList();
    }

    /**
     * The newest group_audit_log rows where the user is the actor or the target, at most limit
     */
    public Mono<List<AuditQueryRepository.Row>> findEvents(UUID userId, int limit) {
        return databaseClient.sql("""
                        SELECT id, actor_id, action, group_id, target_user_id, details, created_at FROM (
                            (SELECT * FROM group_audit_log WHERE actor_id = :userId ORDER BY created_at DESC, id DESC LIMIT :limit)
                            UNION
                            (SELECT * FROM group_audit_log WHERE target_user_id = :userId ORDER BY created_at DESC, id DESC LIMIT :limit)
                        ) AS mine
                        ORDER BY created_at DESC, id DESC
                        LIMIT :limit
                        """)
                .bind("userId", userId)
                .bind("limit", limit)
                .map(row -> new AuditQueryRepository.Row(
                        row.get("id", Long.class),
                        row.get("actor_id", UUID.class),
                        row.get("action", String.class),
                        row.get("group_id", Long.class),
                        row.get("target_user_id", UUID.class),
                        row.get("details", String.class),
                        row.get("created_at", LocalDateTime.class)))
                .all()
                .collectList();
    }
}
