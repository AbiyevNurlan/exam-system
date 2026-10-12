package com.examportal.exam.repository;

import com.examportal.common.domain.entity.UserGroupMember;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * User Group Member Repository
 */
@Repository
public interface UserGroupMemberRepository extends R2dbcRepository<UserGroupMember, Long> {

    /**
     * Add a member; returns 0 when the user is one already (idempotent)
     */
    @Modifying
    @Query("""
        INSERT INTO user_group_members (group_id, user_id, source, added_by, created_at)
        VALUES (:groupId, :userId, :source, :addedBy, CURRENT_TIMESTAMP)
        ON CONFLICT (group_id, user_id) DO NOTHING
    """)
    Mono<Integer> addMember(Long groupId, UUID userId, String source, UUID addedBy);

    @Modifying
    @Query("DELETE FROM user_group_members WHERE group_id = :groupId AND user_id = :userId")
    Mono<Integer> removeMember(Long groupId, UUID userId);

    /**
     * One page of a group's members, most recently added first
     */
    @Query("""
        SELECT * FROM user_group_members
        WHERE group_id = :groupId
        ORDER BY created_at DESC, id DESC
        LIMIT :limit OFFSET :offset
    """)
    Flux<UserGroupMember> findPageByGroupId(Long groupId, int limit, long offset);

    Mono<Long> countByGroupId(Long groupId);
}