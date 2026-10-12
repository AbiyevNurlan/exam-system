package com.examportal.exam.repository;

import com.examportal.common.domain.entity.UserGroup;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;

/**
 * User Group Repository
 * A deleted group is kept with active = false and is invisible to every query here.
 */
@Repository
public interface UserGroupRepository extends R2dbcRepository<UserGroup, Long> {

    @Query("SELECT * FROM user_groups WHERE id = :id AND active = true")
    Mono<UserGroup> findActiveById(Long id);

    /**
     * One page of groups whose lower-case name matches the LIKE pattern (backslash escapes), by name
     */
    @Query("""
        SELECT * FROM user_groups
        WHERE active = true AND lower(name) LIKE :pattern ESCAPE '\\'
        ORDER BY lower(name), id
        LIMIT :limit OFFSET :offset
    """)
    Flux<UserGroup> findPageByNamePattern(String pattern, int limit, long offset);

    @Query("SELECT COUNT(*) FROM user_groups WHERE active = true AND lower(name) LIKE :pattern ESCAPE '\\'")
    Mono<Long> countByNamePattern(String pattern);

    @Query("SELECT COUNT(*) FROM user_groups WHERE active = true AND id IN (:ids)")
    Mono<Long> countActiveByIdIn(Collection<Long> ids);

    @Modifying
    @Query("UPDATE user_groups SET active = false, updated_at = CURRENT_TIMESTAMP WHERE id = :id AND active = true")
    Mono<Integer> softDelete(Long id);
}