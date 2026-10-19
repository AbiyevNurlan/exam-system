package com.examportal.exam.service;

import com.examportal.common.domain.entity.UserGroup;
import com.examportal.exam.exception.GroupException;
import com.examportal.exam.repository.UserGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Group Access Policy
 * The one place that decides who may see and change a group (its settings, members, invites, CSV
 * import, bulk additions, join link and join requests) and which groups an exam audience may use:
 *
 * - ADMIN: every group.
 * - INSTRUCTOR: only the groups they own (user_groups.owner_id).
 * - Anybody else: none.
 *
 * A group the caller may not manage is answered exactly like a group that does not exist (404
 * GROUP_NOT_FOUND), never with a 403, so its existence is not given away. Lists filter in SQL with
 * ownerFilter, never in memory.
 */
@Component
@RequiredArgsConstructor
public class GroupAccessPolicy {

    static final String ADMIN = "ADMIN";
    static final String INSTRUCTOR = "INSTRUCTOR";

    private final UserGroupRepository groupRepository;

    /**
     * Whether the caller may see and change this group
     */
    public static boolean canManage(UserGroup group, UUID userId, String role) {
        return group != null && OwnershipPolicy.owns(userId, role, group.getOwnerId());
    }

    /**
     * The owner a list is limited to: null for ADMIN (every group), the caller for INSTRUCTOR. Any
     * other role gets an owner no group has, so its lists are empty (OwnershipPolicy).
     */
    public static UUID ownerFilter(UUID userId, String role) {
        return OwnershipPolicy.ownerFilter(userId, role);
    }

    /**
     * The active group, if the caller may manage it; otherwise 404 GROUP_NOT_FOUND, the same as for
     * a group that does not exist
     */
    public Mono<UserGroup> findManageableGroup(Long groupId, UUID userId, String role) {
        return groupRepository.findActiveById(groupId)
                .filter(group -> canManage(group, userId, role))
                .switchIfEmpty(Mono.error(GroupException.notFound(groupId)));
    }
}
