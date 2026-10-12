package com.examportal.exam.repository;

import com.examportal.common.domain.entity.PendingGroupInvite;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Pending Group Invite Repository
 */
@Repository
public interface PendingGroupInviteRepository extends R2dbcRepository<PendingGroupInvite, Long> {

    /**
     * Remember an email for a group; returns 0 when it is invited already (idempotent)
     */
    @Modifying
    @Query("""
        INSERT INTO pending_group_invites (group_id, email_normalized, created_by, created_at)
        VALUES (:groupId, :email, :createdBy, CURRENT_TIMESTAMP)
        ON CONFLICT (group_id, email_normalized) DO NOTHING
    """)
    Mono<Integer> addInvite(Long groupId, String email, UUID createdBy);

    @Modifying
    @Query("DELETE FROM pending_group_invites WHERE group_id = :groupId AND email_normalized = :email")
    Mono<Integer> removeInvite(Long groupId, String email);

    Mono<Long> countByGroupId(Long groupId);
}