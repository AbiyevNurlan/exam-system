package com.examportal.exam.service;

import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.group.AddGroupMemberRequest;
import com.examportal.common.domain.dto.group.AddGroupMemberResponse;
import com.examportal.common.domain.dto.group.AddGroupMemberResponse.Status;
import com.examportal.common.domain.dto.group.GroupExamResponse;
import com.examportal.common.domain.dto.group.GroupMemberResponse;
import com.examportal.common.domain.dto.group.GroupRequest;
import com.examportal.common.domain.dto.group.GroupResponse;
import com.examportal.common.domain.dto.group.GroupInviteResponse;
import com.examportal.common.domain.dto.group.GroupSummaryResponse;
import com.examportal.common.domain.dto.internal.ParticipantQuery;
import com.examportal.common.domain.dto.internal.UserLookupResponse.UserRef;
import com.examportal.common.domain.entity.GroupMemberSource;
import com.examportal.common.domain.entity.UserGroup;
import com.examportal.common.domain.entity.UserGroupMember;
import com.examportal.common.utils.LogMaskUtils;
import com.examportal.exam.client.UserDirectoryClient;
import com.examportal.exam.exception.GroupException;
import com.examportal.exam.repository.GroupMembershipRepository;
import com.examportal.exam.repository.GroupOverviewRepository;
import com.examportal.exam.repository.GroupOverviewRepository.GroupFigures;
import com.examportal.exam.repository.GroupOverviewRepository.Membership;
import com.examportal.exam.repository.PendingGroupInviteRepository;
import com.examportal.exam.repository.UserGroupMemberRepository;
import com.examportal.exam.repository.UserGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.examportal.common.exception.ValidationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final GroupOverviewRepository overviewRepository;
    private final GroupMembershipRepository membershipRepository;

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

    /**
     * One group with its member, invite, open request and exam counts and the state of its join link
     * (one statement)
     */
    public Mono<GroupResponse> getGroup(Long groupId) {
        return overviewRepository.findById(groupId)
                .switchIfEmpty(Mono.error(GroupException.notFound(groupId)))
                .map(this::toResponse);
    }

    /**
     * One page of groups sorted by name; the optional filter matches any part of the name, ignoring case.
     * Every row carries its counts and the state of its join link: two statements per page.
     */
    public Mono<PageResponse<GroupResponse>> searchGroups(String name, int page, int size) {
        if (name != null && name.length() > MAX_NAME_LENGTH) {
            return Mono.error(GroupException.invalidSearch());
        }
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String pattern = namePattern(name);

        return groupRepository.countByNamePattern(pattern)
                .flatMap(total -> overviewRepository.findPage(pattern, pageSize, (long) pageNumber * pageSize)
                        .map(this::toResponse)
                        .collectList()
                        .map(rows -> PageResponse.of(rows, pageNumber, pageSize, total)));
    }

    /**
     * Exams (not deleted) whose audience includes the group, newest start first
     */
    public Mono<PageResponse<GroupExamResponse>> getGroupExams(Long groupId, int page, int size) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        return findGroup(groupId)
                .flatMap(group -> overviewRepository.countExamsOfGroup(groupId))
                .flatMap(total -> overviewRepository.findExamsOfGroup(groupId, pageSize, (long) pageNumber * pageSize)
                        .collectList()
                        .map(rows -> PageResponse.of(rows, pageNumber, pageSize, total)));
    }

    /**
     * One page of members. With q (first name, last name, email prefix or a user id; 2 to 100
     * characters) the members are searched the way the participant list searches: the member ids are
     * handed to auth-service, which filters, sorts by name and pages; how and when each row was added
     * comes from one more statement here. Emails stay masked, as in the list without q.
     */
    public Mono<PageResponse<GroupMemberResponse>> getMembers(Long groupId, String q, int page, int size) {
        String term = q == null || q.isBlank() ? null : q.trim();
        if (term == null) {
            return getMembers(groupId, page, size);
        }
        if (term.length() < ParticipantQuery.MIN_SEARCH_LENGTH || term.length() > ParticipantQuery.MAX_SEARCH_LENGTH) {
            return Mono.error(new ValidationException("Search text must be between " + ParticipantQuery.MIN_SEARCH_LENGTH
                    + " and " + ParticipantQuery.MAX_SEARCH_LENGTH + " characters"));
        }
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), Math.min(MAX_PAGE_SIZE, ParticipantQuery.MAX_PAGE_SIZE));

        return findGroup(groupId)
                .flatMap(group -> membershipRepository.findMemberIds(groupId))
                .flatMap(memberIds -> memberIds.isEmpty()
                        ? Mono.just(PageResponse.<GroupMemberResponse>of(List.of(), pageNumber, pageSize, 0))
                        : userDirectory.participants(ParticipantQuery.PageRequest.builder()
                                        .filter(ParticipantQuery.Filter.builder().q(term).includeIds(memberIds).build())
                                        .page(pageNumber)
                                        .size(pageSize)
                                        .sort("name")
                                        .ascending(true)
                                        .build())
                                .flatMap(found -> {
                                    UUID[] ids = found.getUsers().stream().map(ParticipantQuery.Participant::getId).toArray(UUID[]::new);
                                    Mono<Map<UUID, Membership>> memberships = ids.length == 0
                                            ? Mono.just(Map.of())
                                            : overviewRepository.findMemberships(groupId, ids)
                                                    .collectMap(Membership::userId, Function.identity());
                                    return memberships.map(byUser -> PageResponse.of(
                                            found.getUsers().stream().map(user -> {
                                                Membership membership = byUser.get(user.getId());
                                                return GroupMemberResponse.builder()
                                                        .userId(user.getId())
                                                        .firstName(user.getFirstName())
                                                        .lastName(user.getLastName())
                                                        .email(LogMaskUtils.maskEmail(user.getEmail()))
                                                        .source(membership != null ? GroupMemberSource.valueOf(membership.source()) : null)
                                                        .addedBy(membership != null ? membership.addedBy() : null)
                                                        .addedAt(membership != null ? membership.addedAt() : null)
                                                        .build();
                                            }).toList(),
                                            pageNumber, pageSize, found.getTotal()));
                                }));
    }

    public Mono<PageResponse<GroupMemberResponse>> getMembers(Long groupId, int page, int size) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        return findGroup(groupId)
                .flatMap(group -> memberRepository.countByGroupId(groupId))
                .flatMap(total -> memberRepository.findPageByGroupId(groupId, pageSize, (long) pageNumber * pageSize)
                        .collectList()
                        // Names and emails live in auth-service: one lookup for the whole page
                        .flatMap(members -> lookupUsers(members.stream().map(UserGroupMember::getUserId).toList())
                                .map(users -> members.stream()
                                        .map(member -> {
                                            UserRef user = users.get(member.getUserId());
                                            return GroupMemberResponse.builder()
                                                    .userId(member.getUserId())
                                                    .firstName(user != null ? user.getFirstName() : null)
                                                    .lastName(user != null ? user.getLastName() : null)
                                                    .email(user != null ? LogMaskUtils.maskEmail(user.getEmail()) : null)
                                                    .source(member.getSource())
                                                    .addedBy(member.getAddedBy())
                                                    .addedAt(member.getCreatedAt())
                                                    .build();
                                        })
                                        .toList()))
                        .map(rows -> PageResponse.of(rows, pageNumber, pageSize, total)));
    }

    /**
     * Id and name of the active groups, for choosing an exam audience (ADMIN and INSTRUCTOR)
     */
    public Mono<PageResponse<GroupSummaryResponse>> listGroupNames(String name, int page, int size) {
        if (name != null && name.length() > MAX_NAME_LENGTH) {
            return Mono.error(GroupException.invalidSearch());
        }
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String pattern = namePattern(name);

        // Names only: no figures are computed for this list
        return groupRepository.countByNamePattern(pattern)
                .flatMap(total -> groupRepository.findPageByNamePattern(pattern, pageSize, (long) pageNumber * pageSize)
                        .map(group -> GroupSummaryResponse.builder().id(group.getId()).name(group.getName()).build())
                        .collectList()
                        .map(rows -> PageResponse.of(rows, pageNumber, pageSize, total)));
    }

    /**
     * Pending invites of a group, newest first. The email is masked: the list is for seeing how many
     * invites are open and cancelling one, not for reading addresses.
     */
    public Mono<PageResponse<GroupInviteResponse>> getInvites(Long groupId, int page, int size) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        return findGroup(groupId)
                .flatMap(group -> inviteRepository.countByGroupId(groupId))
                .flatMap(total -> inviteRepository.findPageByGroupId(groupId, pageSize, (long) pageNumber * pageSize)
                        .map(invite -> GroupInviteResponse.builder()
                                .id(invite.getId())
                                .email(LogMaskUtils.maskEmail(invite.getEmailNormalized()))
                                .createdBy(invite.getCreatedBy())
                                .createdAt(invite.getCreatedAt())
                                .build())
                        .collectList()
                        .map(rows -> PageResponse.of(rows, pageNumber, pageSize, total)));
    }

    public Mono<Void> cancelInvite(Long groupId, Long inviteId, UUID actorId) {
        Mono<Void> cancellation = findGroup(groupId)
                .flatMap(group -> inviteRepository.deleteInvite(inviteId, groupId))
                .switchIfEmpty(Mono.error(GroupException.inviteNotFound()))
                .flatMap(email -> auditService.record(actorId, GroupAuditService.INVITE_CANCELLED, groupId, null,
                        "email=" + LogMaskUtils.maskEmail(email)));

        return transactionalOperator.transactional(cancellation)
                .doOnSuccess(v -> log.info("Invite {} of group {} cancelled by {}", inviteId, groupId, actorId));
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

    /**
     * Users by id from auth-service in one call. The member list is still useful without names, so
     * a failed lookup only leaves them out.
     */
    private Mono<Map<UUID, UserRef>> lookupUsers(List<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        return userDirectory.lookup(List.of(), userIds)
                .map(users -> users.stream().collect(Collectors.toMap(UserRef::getId, Function.identity(), (a, b) -> a)))
                .onErrorResume(e -> {
                    log.warn("Member names could not be loaded: {}", e.getClass().getSimpleName());
                    return Mono.just(Map.of());
                });
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String namePattern(String name) {
        return "%" + escapeLike(name == null ? "" : name.trim().toLowerCase(Locale.ROOT)) + "%";
    }

    private GroupResponse toResponse(GroupFigures group) {
        GroupResponse.JoinLinkState link = group.linkExists()
                ? GroupResponse.JoinLinkState.builder()
                        .exists(true)
                        .enabled(group.linkEnabled())
                        .usable(Boolean.TRUE.equals(group.linkEnabled())
                                && (group.linkExpiresAt() == null || group.linkExpiresAt().isAfter(LocalDateTime.now()))
                                && (group.linkMaxUses() == null || group.linkUses() < group.linkMaxUses()))
                        .requiresApproval(group.linkRequiresApproval())
                        .uses(group.linkUses())
                        .maxUses(group.linkMaxUses())
                        .build()
                : GroupResponse.JoinLinkState.builder().exists(false).build();
        return GroupResponse.builder()
                .id(group.id())
                .name(group.name())
                .description(group.description())
                .createdBy(group.createdBy())
                .createdAt(group.createdAt())
                .updatedAt(group.updatedAt())
                .memberCount(group.memberCount())
                .pendingInviteCount(group.pendingInviteCount())
                .pendingJoinRequests(group.pendingJoinRequests())
                .examCount(group.examCount())
                .joinLink(link)
                .build();
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