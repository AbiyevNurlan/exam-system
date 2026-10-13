package com.examportal.exam.repository;

import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.UUID;

/**
 * Group Join Repository
 * The join link of a group (group_join_links) and the requests made through a link that needs
 * approval (group_join_requests).
 */
@Repository
@RequiredArgsConstructor
public class GroupJoinRepository {

    private static final String LINK_COLUMNS = "l.id, l.group_id, l.code, l.enabled, l.requires_approval, l.expires_at, "
            + "l.max_uses, l.uses, l.created_by, l.created_at, l.updated_at";

    private final DatabaseClient databaseClient;

    /**
     * groupName is only set by the lookups by code
     */
    public record Link(Long id, Long groupId, String code, boolean enabled, boolean requiresApproval,
                       LocalDateTime expiresAt, Integer maxUses, int uses, UUID createdBy,
                       LocalDateTime createdAt, LocalDateTime updatedAt, String groupName) {

        /**
         * Whether a participant can join with it at the given moment
         */
        public boolean usableAt(LocalDateTime now) {
            return enabled && (expiresAt == null || expiresAt.isAfter(now)) && (maxUses == null || uses < maxUses);
        }
    }

    public record JoinRequest(Long id, UUID userId, String status, LocalDateTime createdAt, UUID decidedBy,
                              LocalDateTime decidedAt) {
    }

    public Mono<Link> findByGroupId(Long groupId) {
        return databaseClient.sql("SELECT " + LINK_COLUMNS + ", NULL::varchar AS group_name FROM group_join_links l WHERE l.group_id = :groupId")
                .bind("groupId", groupId)
                .map(GroupJoinRepository::toLink)
                .one();
    }

    /**
     * The link of a group, locked until the end of the surrounding transaction
     */
    public Mono<Link> lockByGroupId(Long groupId) {
        return databaseClient.sql("SELECT " + LINK_COLUMNS + ", NULL::varchar AS group_name FROM group_join_links l "
                        + "WHERE l.group_id = :groupId FOR UPDATE")
                .bind("groupId", groupId)
                .map(GroupJoinRepository::toLink)
                .one();
    }

    /**
     * The link with this code, if its group still exists
     */
    public Mono<Link> findByCode(String code) {
        return databaseClient.sql("SELECT " + LINK_COLUMNS + ", g.name AS group_name FROM group_join_links l "
                        + "JOIN user_groups g ON g.id = l.group_id AND g.active = true WHERE l.code = :code")
                .bind("code", code)
                .map(GroupJoinRepository::toLink)
                .one();
    }

    /**
     * As findByCode, with the link row locked until the end of the surrounding transaction: the
     * joins through one link take turns, so each one sees the uses the others have counted
     */
    public Mono<Link> lockByCode(String code) {
        return databaseClient.sql("SELECT " + LINK_COLUMNS + ", g.name AS group_name FROM group_join_links l "
                        + "JOIN user_groups g ON g.id = l.group_id AND g.active = true WHERE l.code = :code FOR UPDATE OF l")
                .bind("code", code)
                .map(GroupJoinRepository::toLink)
                .one();
    }

    /**
     * Create the link of a group, or replace it: a new code, counted from zero and switched on.
     * Fails with a duplicate key when the code is taken (the caller tries another one).
     */
    public Mono<Link> upsert(Long groupId, String code, boolean requiresApproval, LocalDateTime expiresAt,
                             Integer maxUses, UUID createdBy, LocalDateTime now) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        INSERT INTO group_join_links AS l (group_id, code, enabled, requires_approval, expires_at, max_uses,
                                                           uses, created_by, created_at, updated_at)
                        VALUES (:groupId, :code, true, :requiresApproval, :expiresAt, :maxUses, 0, :createdBy, :now, :now)
                        ON CONFLICT (group_id) DO UPDATE
                        SET code = EXCLUDED.code, enabled = true, requires_approval = EXCLUDED.requires_approval,
                            expires_at = EXCLUDED.expires_at, max_uses = EXCLUDED.max_uses, uses = 0,
                            created_by = EXCLUDED.created_by, updated_at = EXCLUDED.updated_at
                        RETURNING l.id, l.group_id, l.code, l.enabled, l.requires_approval, l.expires_at, l.max_uses, l.uses,
                                  l.created_by, l.created_at, l.updated_at, NULL::varchar AS group_name
                        """)
                .bind("groupId", groupId)
                .bind("code", code)
                .bind("requiresApproval", requiresApproval)
                .bind("createdBy", createdBy)
                .bind("now", now);
        spec = expiresAt != null ? spec.bind("expiresAt", expiresAt) : spec.bindNull("expiresAt", LocalDateTime.class);
        spec = maxUses != null ? spec.bind("maxUses", maxUses) : spec.bindNull("maxUses", Integer.class);
        return spec.map(GroupJoinRepository::toLink).one();
    }

    public Mono<Long> updateSettings(Long linkId, boolean enabled, boolean requiresApproval, LocalDateTime expiresAt,
                                     Integer maxUses, LocalDateTime now) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        UPDATE group_join_links
                        SET enabled = :enabled, requires_approval = :requiresApproval, expires_at = :expiresAt,
                            max_uses = :maxUses, updated_at = :now
                        WHERE id = :linkId
                        """)
                .bind("linkId", linkId)
                .bind("enabled", enabled)
                .bind("requiresApproval", requiresApproval)
                .bind("now", now);
        spec = expiresAt != null ? spec.bind("expiresAt", expiresAt) : spec.bindNull("expiresAt", LocalDateTime.class);
        spec = maxUses != null ? spec.bind("maxUses", maxUses) : spec.bindNull("maxUses", Integer.class);
        return spec.fetch().rowsUpdated();
    }

    /**
     * Count one use; call it while holding the lock of lockByCode
     */
    public Mono<Long> countUse(Long linkId, LocalDateTime now) {
        return databaseClient.sql("UPDATE group_join_links SET uses = uses + 1, updated_at = :now WHERE id = :linkId")
                .bind("linkId", linkId)
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    public Mono<Boolean> isMember(Long groupId, UUID userId) {
        return databaseClient.sql("SELECT EXISTS (SELECT 1 FROM user_group_members WHERE group_id = :groupId AND user_id = :userId)")
                .bind("groupId", groupId)
                .bind("userId", userId)
                .map(row -> Boolean.TRUE.equals(row.get(0, Boolean.class)))
                .one();
    }

    public Mono<Boolean> hasPendingRequest(Long groupId, UUID userId) {
        return databaseClient.sql("SELECT EXISTS (SELECT 1 FROM group_join_requests "
                        + "WHERE group_id = :groupId AND user_id = :userId AND status = 'PENDING')")
                .bind("groupId", groupId)
                .bind("userId", userId)
                .map(row -> Boolean.TRUE.equals(row.get(0, Boolean.class)))
                .one();
    }

    /**
     * Open a request; returns 0 when the user has an open one already (uq_group_join_requests_pending)
     */
    public Mono<Long> addRequest(Long groupId, UUID userId, LocalDateTime now) {
        return databaseClient.sql("""
                        INSERT INTO group_join_requests (group_id, user_id, status, created_at)
                        VALUES (:groupId, :userId, 'PENDING', :now)
                        ON CONFLICT (group_id, user_id) WHERE status = 'PENDING' DO NOTHING
                        """)
                .bind("groupId", groupId)
                .bind("userId", userId)
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    public Mono<Long> countRequests(Long groupId, String status) {
        return databaseClient.sql("SELECT COUNT(*) FROM group_join_requests WHERE group_id = :groupId AND status = :status")
                .bind("groupId", groupId)
                .bind("status", status)
                .map(row -> row.get(0, Long.class))
                .one();
    }

    /**
     * One page of a group's requests in one status, oldest first (idx_group_join_requests_group)
     */
    public Flux<JoinRequest> findRequests(Long groupId, String status, int limit, long offset) {
        return databaseClient.sql("""
                        SELECT id, user_id, status, created_at, decided_by, decided_at
                        FROM group_join_requests
                        WHERE group_id = :groupId AND status = :status
                        ORDER BY created_at ASC, id ASC
                        LIMIT :limit OFFSET :offset
                        """)
                .bind("groupId", groupId)
                .bind("status", status)
                .bind("limit", limit)
                .bind("offset", offset)
                .map(row -> new JoinRequest(
                        row.get("id", Long.class),
                        row.get("user_id", UUID.class),
                        row.get("status", String.class),
                        row.get("created_at", LocalDateTime.class),
                        row.get("decided_by", UUID.class),
                        row.get("decided_at", LocalDateTime.class)))
                .all();
    }

    /**
     * Approve or reject the open requests among the given ids, in ONE atomic statement: the requests
     * are decided, an approval creates the membership (source LINK, added by the approver), and every
     * decided request gets its audit row. A request that is not open (or of another group) is skipped.
     * Emits how many were decided.
     */
    public Mono<Integer> decide(Long groupId, Collection<Long> requestIds, boolean approve, UUID actorId, LocalDateTime now) {
        String membership = approve
                ? """
                  , added AS (
                      INSERT INTO user_group_members (group_id, user_id, source, added_by, created_at)
                      SELECT :groupId, d.user_id, 'LINK', :actorId, :now FROM decided d
                      ON CONFLICT (group_id, user_id) DO NOTHING
                      RETURNING user_id
                  )
                  """
                : "";
        return databaseClient.sql("""
                        WITH decided AS (
                            UPDATE group_join_requests
                            SET status = :status, decided_by = :actorId, decided_at = :now
                            WHERE group_id = :groupId AND id = ANY(:requestIds) AND status = 'PENDING'
                            RETURNING user_id
                        )%s, logged AS (
                            INSERT INTO group_audit_log (actor_id, action, group_id, target_user_id, details, created_at)
                            SELECT :actorId, :action, :groupId, d.user_id, :details, :now FROM decided d
                            RETURNING id
                        )
                        SELECT COUNT(*)::int AS decided FROM decided
                        """.formatted(membership))
                .bind("groupId", groupId)
                .bind("requestIds", requestIds.toArray(new Long[0]))
                .bind("status", approve ? "APPROVED" : "REJECTED")
                .bind("action", approve ? "JOIN_APPROVED" : "JOIN_REJECTED")
                .bind("details", approve ? "join request approved" : "join request rejected")
                .bind("actorId", actorId)
                .bind("now", now)
                .map(row -> row.get("decided", Integer.class))
                .one();
    }

    private static Link toLink(Readable row) {
        Integer uses = row.get("uses", Integer.class);
        return new Link(
                row.get("id", Long.class),
                row.get("group_id", Long.class),
                row.get("code", String.class),
                Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                Boolean.TRUE.equals(row.get("requires_approval", Boolean.class)),
                row.get("expires_at", LocalDateTime.class),
                row.get("max_uses", Integer.class),
                uses == null ? 0 : uses,
                row.get("created_by", UUID.class),
                row.get("created_at", LocalDateTime.class),
                row.get("updated_at", LocalDateTime.class),
                row.get("group_name", String.class));
    }
}
