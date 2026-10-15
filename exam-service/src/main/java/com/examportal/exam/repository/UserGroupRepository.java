package com.examportal.exam.repository;

import com.examportal.common.domain.entity.UserGroup;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.UUID;

/**
 * User Group Repository
 * A deleted group is kept with active = false and is invisible to every query here.
 */
@Repository
public interface UserGroupRepository extends R2dbcRepository<UserGroup, Long> {

    @Query("SELECT * FROM user_groups WHERE id = :id AND active = true")
    Mono<UserGroup> findActiveById(Long id);

    /**
     * One page of groups whose lower-case name matches the LIKE pattern (backslash escapes), by name.
     * ownerId limits it to the groups of that owner (GroupAccessPolicy.ownerFilter); null = all.
     */
    @Query("""
        SELECT * FROM user_groups
        WHERE active = true AND lower(name) LIKE :pattern ESCAPE '\\'
          AND (CAST(:ownerId AS uuid) IS NULL OR owner_id = CAST(:ownerId AS uuid))
        ORDER BY lower(name), id
        LIMIT :limit OFFSET :offset
    """)
    Flux<UserGroup> findPageByNamePattern(String pattern, UUID ownerId, int limit, long offset);

    @Query("""
        SELECT COUNT(*) FROM user_groups
        WHERE active = true AND lower(name) LIKE :pattern ESCAPE '\\'
          AND (CAST(:ownerId AS uuid) IS NULL OR owner_id = CAST(:ownerId AS uuid))
    """)
    Mono<Long> countByNamePattern(String pattern, UUID ownerId);

    @Query("SELECT COUNT(*) FROM user_groups WHERE active = true AND id IN (:ids)")
    Mono<Long> countActiveByIdIn(Collection<Long> ids);

    /**
     * Those of the given ids that are active groups the caller may use: every one for ownerId null
     * (ADMIN), otherwise the owner's groups
     */
    @Query("""
        SELECT id FROM user_groups
        WHERE active = true AND id IN (:ids)
          AND (CAST(:ownerId AS uuid) IS NULL OR owner_id = CAST(:ownerId AS uuid))
    """)
    Flux<Long> findManageableIds(Collection<Long> ids, UUID ownerId);

    @Modifying
    @Query("UPDATE user_groups SET active = false, updated_at = CURRENT_TIMESTAMP WHERE id = :id AND active = true")
    Mono<Integer> softDelete(Long id);
}