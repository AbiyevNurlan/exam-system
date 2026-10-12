package com.examportal.exam.service;

import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.group.AddGroupMemberRequest;
import com.examportal.common.domain.dto.group.AddGroupMemberResponse;
import com.examportal.common.domain.dto.group.AddGroupMemberResponse.Status;
import com.examportal.common.domain.dto.group.GroupMemberResponse;
import com.examportal.common.domain.dto.group.GroupRequest;
import com.examportal.common.domain.dto.group.GroupResponse;
import com.examportal.common.domain.entity.GroupMemberSource;
import com.examportal.common.domain.entity.UserGroup;
import com.examportal.common.utils.LogMaskUtils;
import com.examportal.exam.client.UserDirectoryClient;
import com.examportal.exam.exception.GroupException;
import com.examportal.exam.repository.PendingGroupInviteRepository;
import com.examportal.exam.repository.UserGroupMemberRepository;
import com.examportal.exam.repository.UserGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * Group Service
 * Groups and their members (ADMIN only; the controller enforces the role).
 * Every change and its group_audit_log row are written in one transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_NAME_LENGTH = 100;

    private final UserGroupRepository groupRepository;
    private final UserGroupMemberRepository memberRepository;
    private final PendingGroupInviteRepository inviteRepository;
    private final GroupAuditService auditService;
    private final UserDirectoryClient userDirectory;
    private final TransactionalOperator transactionalOperator;

    public Mono<GroupResponse> createGroup(GroupRequest request, UUID actorId) {
        LocalDateTime now = LocalDateTime.now();
        UserGroup group = UserGroup.builder()
                .name(request.getName().trim())
                .description(request.getDescription())
                .createdBy(actorId)
                .createdAt(now)
                .updatedAt(now)
                .active(true)
                .build();

        Mono<UserGroup> creation = groupRepository.save(group)
                .flatMap(saved -> auditService.record(actorId, GroupAuditService.GROUP_CREATED, saved.getId(), null,
                                "name=" + saved.getName())
                        .thenReturn(saved));

        return transactionalOperator.transactional(creation)
                .onErrorMap(DuplicateKeyException.class, e -> GroupException.nameExists())
                .map(this::toResponse)
                .doOnSuccess(created -> log.info("Group {} created by {}", created.getId(), actorId));
    }

    public Mono<GroupResponse> updateGroup(Long groupId, GroupRequest request, UUID actorId) {
        Mono<UserGroup> update = findGroup(groupId)
                .flatMap(group -> {
                    String oldName = group.getName();
                    group.setName(request.getName().trim());
                    group.setDescription(request.getDescription());
                    group.setUpdatedAt(LocalDateTime.now());
                    return groupRepository.save(group)
                            .flatMap(saved -> auditService.record(actorId, GroupAuditService.GROUP_UPDATED, groupId,
                                            null, "name=" + oldName + " -> " + saved.getName())
                                    .thenReturn(saved));
                });

        return transactionalOperator.transactional(update)
                .onErrorMap(DuplicateKeyException.class, e -> GroupException.nameExists())
                .map(this::toResponse)
                .doOnSuccess(updated -> log.info("Group {} updated by {}", groupId, actorId));
    }

    /**
     * Soft delete: the group, its members and its audit trail stay in the database. Exams that
     * had only this group as audience are from now on accessible to nobody but their owner and ADMIN.
     */
    public Mono<Void> deleteGroup(Long groupId, UUID actorId) {
        Mono<Void> deletion = findGroup(groupId)
                .flatMap(group -> groupRepository.softDelete(groupId)
                        .then(auditService.record(actorId, GroupAuditService.GROUP_DELETED, groupId, null,
                                "name=" + group.getName())));

        return transactionalOperator.transactional(deletion)
                .doOnSuccess(v -> log.info("Group {} deleted by {}", groupId, actorId));
    }

    public Mono<GroupResponse> getGroup(Long groupId) {
        return findGroup(groupId)
                .flatMap(group -> Mono.zip(memberRepository.countByGroupId(groupId),
                                inviteRepository.countByGroupId(groupId))
                        .map(counts -> {
                            GroupResponse response = toResponse(group);
                            response.setMemberCount(counts.getT1());
                            response.setPendingInviteCount(counts.getT2());
                            return response;
                        }));
    }

    /**
     * One page of groups sorted by name; the optional filter matches any part of the name, ignoring case
     */
    public Mono<PageResponse<GroupResponse>> searchGroups(String name, int page, int size) {
        if (name != null && name.length() > MAX_NAME_LENGTH) {
            return Mono.error(GroupException.invalidSearch());
        }
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String pattern = "%" + escapeLike(name == null ? "" : name.trim().toLowerCase(Locale.ROOT)) + "%";

        return groupRepository.countByNamePattern(pattern)
                .flatMap(total -> groupRepository.findPageByNamePattern(pattern, pageSize, (long) pageNumber * pageSize)
                        .map(this::toResponse)
                        .collectList()
                        .map(rows -> PageResponse.of(rows, pageNumber, pageSize, total)));
    }

    public Mono<PageResponse<GroupMemberResponse>> getMembers(Long groupId, int page, int size) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        return findGroup(groupId)
                .flatMap(group -> memberRepository.countByGroupId(groupId))
                .flatMap(total -> memberRepository.findPageByGroupId(groupId, pageSize, (long) pageNumber * pageSize)
                        .map(member -> GroupMemberResponse.builder()
                                .userId(member.getUserId())
                                .source(member.getSource())
                                .addedBy(member.getAddedBy())
                                .addedAt(member.getCreatedAt())
                                .build())
                        .collectList()
                        .map(rows -> PageResponse.of(rows, pageNumber, pageSize, total)));
    }

    /**
     * Add one member by user id or by email.
     * By id: the account must exist. By email: an account with that verified email becomes a member;
     * otherwise the email is stored as a pending invite and applied once such an account exists
     * (an unverified account must not gain access through an address it has not proven to own).
     */
    public Mono<AddGroupMemberResponse> addMember(Long groupId, AddGroupMemberRequest request, UUID actorId) {
        boolean hasId = request.getUserId() != null;
        boolean hasEmail = request.getEmail() != null && !request.getEmail().isBlank();
        if (hasId == hasEmail) {
            return Mono.error(GroupException.memberReferenceRequired());
        }

        return findGroup(groupId).flatMap(group -> {
            if (hasId) {
                return userDirectory.findById(request.getUserId())
                        .switchIfEmpty(Mono.error(GroupException.userNotFound()))
                        .flatMap(user -> addExistingUser(groupId, user.getId(), null, actorId));
            }
            String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
            return userDirectory.findByEmail(email)
                    .filter(user -> user.isEmailVerified())
                    .flatMap(user -> addExistingUser(groupId, user.getId(), email, actorId))
                    .switchIfEmpty(Mono.defer(() -> invite(groupId, email, actorId)));
        });
    }

    public Mono<Void> removeMember(Long groupId, UUID userId, UUID actorId) {
        Mono<Void> removal = findGroup(groupId)
                .flatMap(group -> memberRepository.removeMember(groupId, userId))
                .flatMap(removed -> removed == 0
                        ? Mono.<Void>error(GroupException.memberNotFound())
                        : auditService.record(actorId, GroupAuditService.MEMBER_REMOVED, groupId, userId, null));

        return transactionalOperator.transactional(removal)
                .doOnSuccess(v -> log.info("User {} removed from group {} by {}", userId, groupId, actorId));
    }

    // ==================== Private Helper Methods ====================

    private Mono<UserGroup> findGroup(Long groupId) {
        return groupRepository.findActiveById(groupId)
                .switchIfEmpty(Mono.error(GroupException.notFound(groupId)));
    }

    private Mono<AddGroupMemberResponse> addExistingUser(Long groupId, UUID userId, String email, UUID actorId) {
        Mono<Status> addition = memberRepository.addMember(groupId, userId, GroupMemberSource.ADMIN.name(), actorId)
                .flatMap(added -> {
                    // The membership replaces an invite for the same address, if there was one
                    Mono<Integer> dropInvite = email != null ? inviteRepository.removeInvite(groupId, email) : Mono.just(0);
                    if (added == 0) {
                        return dropInvite.thenReturn(Status.ALREADY_MEMBER);
                    }
                    return dropInvite
                            .then(auditService.record(actorId, GroupAuditService.MEMBER_ADDED, groupId, userId,
                                    "source=ADMIN"))
                            .thenReturn(Status.ADDED);
                });

        return transactionalOperator.transactional(addition)
                .map(status -> AddGroupMemberResponse.builder().status(status).userId(userId).build());
    }

    private Mono<AddGroupMemberResponse> invite(Long groupId, String email, UUID actorId) {
        Mono<Status> invitation = inviteRepository.addInvite(groupId, email, actorId)
                .flatMap(added -> added == 0
                        ? Mono.just(Status.ALREADY_INVITED)
                        : auditService.record(actorId, GroupAuditService.INVITE_CREATED, groupId, null,
                                        "email=" + LogMaskUtils.maskEmail(email))
                                .thenReturn(Status.INVITED));

        return transactionalOperator.transactional(invitation)
                .map(status -> AddGroupMemberResponse.builder().status(status).build());
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private GroupResponse toResponse(UserGroup group) {
        return GroupResponse.builder()
                .id(group.getId())
                .name(group.getName())
                .description(group.getDescription())
                .createdBy(group.getCreatedBy())
                .createdAt(group.getCreatedAt())
                .updatedAt(group.getUpdatedAt())
                .build();
    }
}