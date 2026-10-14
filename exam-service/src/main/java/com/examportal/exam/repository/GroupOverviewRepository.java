package com.examportal.exam.repository;

import com.examportal.common.domain.dto.group.GroupExamResponse;
import com.examportal.common.domain.entity.ExamAccessType;
import com.examportal.common.domain.entity.ExamStatus;
import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Group Overview Repository
 * The figures of the group list in one statement per page: the page of groups is cut first, then
 * members, pending invites, open join requests, exams and the join link are added for those rows only
 * (each from its own index), so the cost does not grow with the number of groups or members.
 */
@Repository
@RequiredArgsConstructor
public class GroupOverviewRepository {

    private final DatabaseClient databaseClient;

    public record GroupFigures(Long id, String name, String description, UUID createdBy, LocalDateTime createdAt,
                               LocalDateTime updatedAt, long memberCount, long pendingInviteCount,
                               long pendingJoinRequests, long examCount, boolean linkExists, Boolean linkEnabled,
                               Boolean linkRequiresApproval, LocalDateTime linkExpiresAt, Integer linkMaxUses,
                               Integer linkUses) {
    }

    // Figures of the groups in "g" (an inline view of user_groups rows); fixed text only
    private static final String FIGURES = """
            SELECT g.id, g.name, g.description, g.created_by, g.created_at, g.updated_at,
                   m.member_count, i.invite_count, r.request_count, x.exam_count,
                   l.id IS NOT NULL AS link_exists, l.enabled AS link_enabled,
                   l.requires_approval AS link_requires_approval, l.expires_at AS link_expires_at,
                   l.max_uses AS link_max_uses, l.uses AS link_uses
            FROM (%s) g
            CROSS JOIN LATERAL (SELECT COUNT(*) AS member_count FROM user_group_members WHERE group_id = g.id) m
            CROSS JOIN LATERAL (SELECT COUNT(*) AS invite_count FROM pending_group_invites WHERE group_id = g.id) i
            CROSS JOIN LATERAL (SELECT COUNT(*) AS request_count FROM group_join_requests
                                WHERE group_id = g.id AND status = 'PENDING') r
            CROSS JOIN LATERAL (SELECT COUNT(*) AS exam_count FROM exam_audience a
                                JOIN exams e ON e.id = a.exam_id AND e.active = true AND e.access_type = 'GROUP'
                                WHERE a.group_id = g.id) x
            LEFT JOIN group_join_links l ON l.group_id = g.id
            """;

    /**
     * One page of active groups whose lower-case name matches the LIKE pattern, by name, with figures
     */
    public Flux<GroupFigures> findPage(String pattern, int limit, long offset) {
        return databaseClient.sql(FIGURES.formatted("""
                        SELECT * FROM user_groups
                        WHERE active = true AND lower(name) LIKE :pattern ESCAPE '\\'
                        ORDER BY lower(name), id
                        LIMIT :limit OFFSET :offset""") + " ORDER BY lower(g.name), g.id")
                .bind("pattern", pattern)
                .bind("limit", limit)
                .bind("offset", offset)
                .map(GroupOverviewRepository::figures)
                .all();
    }

    /**
     * One active group with its figures
     */
    public Mono<GroupFigures> findById(Long groupId) {
        return databaseClient.sql(FIGURES.formatted("SELECT * FROM user_groups WHERE id = :groupId AND active = true"))
                .bind("groupId", groupId)
                .map(GroupOverviewRepository::figures)
                .one();
    }

    /**
     * Exams (not deleted) whose audience includes the group, newest start first
     */
    public Flux<GroupExamResponse> findExamsOfGroup(Long groupId, int limit, long offset) {
        return databaseClient.sql("""
                        SELECT e.id, e.title, e.status, e.start_time, e.end_time, e.access_type
                        FROM exam_audience a
                        JOIN exams e ON e.id = a.exam_id AND e.active = true AND e.access_type = 'GROUP'
                        WHERE a.group_id = :groupId
                        ORDER BY e.start_time DESC, e.id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .bind("groupId", groupId)
                .bind("limit", limit)
                .bind("offset", offset)
                .map(row -> GroupExamResponse.builder()
                        .id(row.get("id", Long.class))
                        .title(row.get("title", String.class))
                        .status(ExamStatus.valueOf(row.get("status", String.class)))
                        .startTime(row.get("start_time", LocalDateTime.class))
                        .endTime(row.get("end_time", LocalDateTime.class))
                        .accessType(ExamAccessType.valueOf(row.get("access_type", String.class)))
                        .build())
                .all();
    }

    public Mono<Long> countExamsOfGroup(Long groupId) {
        return databaseClient.sql("""
                        SELECT COUNT(*) FROM exam_audience a
                        JOIN exams e ON e.id = a.exam_id AND e.active = true AND e.access_type = 'GROUP'
                        WHERE a.group_id = :groupId
                        """)
                .bind("groupId", groupId)
                .map(row -> row.get(0, Long.class))
                .one();
    }

    /**
     * Source and time of addition of the given members of a group
     */
    public Flux<Membership> findMemberships(Long groupId, UUID[] userIds) {
        return databaseClient.sql("""
                        SELECT user_id, source, added_by, created_at FROM user_group_members
                        WHERE group_id = :groupId AND user_id = ANY(:userIds)
                        """)
                .bind("groupId", groupId)
                .bind("userIds", userIds)
                .map(row -> new Membership(
                        row.get("user_id", UUID.class),
                        row.get("source", String.class),
                        row.get("added_by", UUID.class),
                        row.get("created_at", LocalDateTime.class)))
                .all();
    }

    public record Membership(UUID userId, String source, UUID addedBy, LocalDateTime addedAt) {
    }

    private static GroupFigures figures(Readable row) {
        return new GroupFigures(
                row.get("id", Long.class),
                row.get("name", String.class),
                row.get("description", String.class),
                row.get("created_by", UUID.class),
                row.get("created_at", LocalDateTime.class),
                row.get("updated_at", LocalDateTime.class),
                row.get("member_count", Long.class),
                row.get("invite_count", Long.class),
                row.get("request_count", Long.class),
                row.get("exam_count", Long.class),
                Boolean.TRUE.equals(row.get("link_exists", Boolean.class)),
                row.get("link_enabled", Boolean.class),
                row.get("link_requires_approval", Boolean.class),
                row.get("link_expires_at", LocalDateTime.class),
                row.get("link_max_uses", Integer.class),
                row.get("link_uses", Integer.class));
    }
}
