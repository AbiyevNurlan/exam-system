package com.examportal.exam.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Group Membership Repository
 * Set-based reads and writes of memberships for the participant list, the bulk additions and the
 * audience preview: one statement per set of users or groups, never one per row. Arrays are bound
 * as a single parameter. "Group" always means an active (not deleted) group.
 */
@Repository
@RequiredArgsConstructor
public class GroupMembershipRepository {

    private final DatabaseClient databaseClient;

    public record UserGroupName(UUID userId, Long groupId, String groupName) {
    }

    public record GroupMembers(Long groupId, String name, long members) {
    }

    /**
     * The groups of each of the given users, by group name (idx_group_members_user_id)
     */
    public Flux<UserGroupName> findGroupsOfUsers(Collection<UUID> userIds) {
        return findGroupsOfUsers(userIds, null);
    }

    /**
     * The groups of each of the given users, by group name; owner limits them to the groups of that owner (B13: a
     * teacher sees only the own groups of a participant, never the names of another owner's groups); null = all
     */
    public Flux<UserGroupName> findGroupsOfUsers(Collection<UUID> userIds, UUID owner) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        SELECT m.user_id, g.id, g.name
                        FROM user_group_members m
                        JOIN user_groups g ON g.id = m.group_id AND g.active = true
                        WHERE m.user_id = ANY(:userIds)
                          AND (CAST(:owner AS uuid) IS NULL OR g.owner_id = CAST(:owner AS uuid))
                        ORDER BY m.user_id, btrim(g.name) COLLATE az_ci, g.id
                        """)
                .bind("userIds", userIds.toArray(new UUID[0]));
        return (owner != null ? spec.bind("owner", owner) : spec.bindNull("owner", UUID.class))
                .map(row -> new UserGroupName(
                        row.get("user_id", UUID.class),
                        row.get("id", Long.class),
                        row.get("name", String.class)))
                .all();
    }

    /**
     * The ids of the members of one group
     */
    public Mono<List<UUID>> findMemberIds(Long groupId) {
        return databaseClient.sql("SELECT user_id FROM user_group_members WHERE group_id = :groupId")
                .bind("groupId", groupId)
                .map(row -> row.get("user_id", UUID.class))
                .all()
                .collectList();
    }

    /**
     * B13: whether the user is a member of an active group of the owner (someone the teacher knows already)
     */
    public Mono<Boolean> isMemberOfOwner(UUID userId, UUID owner) {
        return databaseClient.sql("""
                        SELECT EXISTS (SELECT 1 FROM user_group_members m
                                       JOIN user_groups g ON g.id = m.group_id AND g.active = true
                                       WHERE m.user_id = :userId AND g.owner_id = :owner) AS member
                        """)
                .bind("userId", userId)
                .bind("owner", owner)
                .map(row -> Boolean.TRUE.equals(row.get("member", Boolean.class)))
                .one();
    }

    /**
     * B13: the ids of everybody who is a member of at least one active group of the owner (a teacher's participants)
     */
    public Mono<List<UUID>> findMemberIdsOfOwner(UUID owner) {
        return databaseClient.sql("""
                        SELECT DISTINCT m.user_id
                        FROM user_groups g
                        JOIN user_group_members m ON m.group_id = g.id
                        WHERE g.owner_id = :owner AND g.active = true
                        """)
                .bind("owner", owner)
                .map(row -> row.get("user_id", UUID.class))
                .all()
                .collectList();
    }

    /**
     * The ids of everybody who is a member of at least one group
     */
    public Mono<List<UUID>> findAllMemberIds() {
        return databaseClient.sql("""
                        SELECT DISTINCT m.user_id
                        FROM user_group_members m
                        JOIN user_groups g ON g.id = m.group_id AND g.active = true
                        """)
                .map(row -> row.get("user_id", UUID.class))
                .all()
                .collectList();
    }

    /**
     * How many of the given users are members of the group already
     */
    public Mono<Long> countMembersAmong(Long groupId, Collection<UUID> userIds) {
        return databaseClient.sql("""
                        SELECT COUNT(*) FROM user_group_members
                        WHERE group_id = :groupId AND user_id = ANY(:userIds)
                        """)
                .bind("groupId", groupId)
                .bind("userIds", userIds.toArray(new UUID[0]))
                .map(row -> row.get(0, Long.class))
                .one();
    }

    /**
     * Add the users as members; returns how many were new. Idempotent: a user who is a member
     * already is skipped (ON CONFLICT DO NOTHING), also when two requests add the same user at once.
     */
    public Mono<Long> addMembers(Long groupId, Collection<UUID> userIds, String source, UUID addedBy) {
        return databaseClient.sql("""
                        INSERT INTO user_group_members (group_id, user_id, source, added_by, created_at)
                        SELECT :groupId, u, :source, :addedBy, CURRENT_TIMESTAMP
                        FROM unnest(CAST(:userIds AS UUID[])) AS u
                        ON CONFLICT (group_id, user_id) DO NOTHING
                        """)
                .bind("groupId", groupId)
                .bind("source", source)
                .bind("addedBy", addedBy)
                .bind("userIds", userIds.toArray(new UUID[0]))
                .fetch()
                .rowsUpdated();
    }

    /**
     * Name and member count of each of the given groups that exists
     */
    public Flux<GroupMembers> countMembersPerGroup(Collection<Long> groupIds) {
        return databaseClient.sql("""
                        SELECT g.id, g.name, COUNT(m.user_id) AS members
                        FROM user_groups g
                        LEFT JOIN user_group_members m ON m.group_id = g.id
                        WHERE g.id = ANY(:groupIds) AND g.active = true
                        GROUP BY g.id
                        ORDER BY btrim(g.name) COLLATE az_ci, g.id
                        """)
                .bind("groupIds", groupIds.toArray(new Long[0]))
                .map(row -> new GroupMembers(
                        row.get("id", Long.class),
                        row.get("name", String.class),
                        row.get("members", Long.class)))
                .all();
    }

    /**
     * How many different users are a member of at least one of the given groups: someone in two of
     * them is counted once
     */
    public Mono<Long> countDistinctMembers(Collection<Long> groupIds) {
        return databaseClient.sql("""
                        SELECT COUNT(DISTINCT m.user_id)
                        FROM user_group_members m
                        JOIN user_groups g ON g.id = m.group_id AND g.active = true
                        WHERE m.group_id = ANY(:groupIds)
                        """)
                .bind("groupIds", groupIds.toArray(new Long[0]))
                .map(row -> row.get(0, Long.class))
                .one();
    }
}
