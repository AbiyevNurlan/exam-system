package com.examportal.exam.service;

import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.group.JoinDtos;
import com.examportal.common.domain.dto.internal.UserLookupResponse.UserRef;
import com.examportal.common.domain.entity.GroupMemberSource;
import com.examportal.common.exception.ValidationException;
import com.examportal.common.security.UserPrincipal;
import com.examportal.exam.client.UserDirectoryClient;
import com.examportal.exam.exception.GroupException;
import com.examportal.exam.repository.GroupJoinRepository;
import com.examportal.exam.repository.GroupJoinRepository.JoinRequest;
import com.examportal.exam.repository.GroupJoinRepository.Link;
import com.examportal.exam.repository.UserGroupMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Group Join Service
 * Joining a group with a link. Whoever manages the group (ADMIN, or the INSTRUCTOR who owns it:
 * GroupAccessPolicy) creates one link per group (creating it again
 * replaces the code), can switch it off, give it an end time and a maximum number of uses, and can
 * make every join a request that needs approval. A participant with a verified email opens the
 * link and joins.
 *
 * Whatever is wrong with a code (unknown, switched off, expired, used up, group deleted) is
 * answered the same way, JOIN_CODE_INVALID, and the link never shows anything but the group's name.
 * Joining through one link takes turns (the link row is locked), so max_uses is never exceeded and
 * a repeated join is counted once. Every change is written with its audit row in one transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupJoinService {

    // No 0, O, 1 and I: 32 characters, 5 bits each, 50 bits in a code
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 10;
    private static final int MAX_PAGE_SIZE = 50;
    private static final String STUDENT = "STUDENT";
    private static final String STATUS_MEMBER = "MEMBER";
    private static final String STATUS_PENDING = "PENDING";
    private static final Set<String> REQUEST_STATUSES = Set.of("PENDING", "APPROVED", "REJECTED");

    private final SecureRandom random = new SecureRandom();

    private final UserGroupMemberRepository memberRepository;
    private final GroupJoinRepository joinRepository;
    private final GroupAuditService auditService;
    private final UserDirectoryClient userDirectory;
    private final ParticipantDirectory participants;
    private final TransactionalOperator transactionalOperator;
    private final GroupAccessPolicy accessPolicy;

    // ==================== Administrator ====================

    public Mono<JoinDtos.LinkResponse> getLink(Long groupId, UserPrincipal actor) {
        return findGroup(groupId, actor)
                .then(Mono.defer(() -> joinRepository.findByGroupId(groupId)))
                .switchIfEmpty(Mono.error(GroupException.joinLinkNotFound()))
                .flatMap(this::toResponse);
    }

    /**
     * Create the link of a group, or create it again: a new code (the old one stops working), the
     * uses counted from zero, switched on. Settings that are not given keep their value.
     */
    public Mono<JoinDtos.LinkResponse> createLink(Long groupId, JoinDtos.LinkRequest request, UserPrincipal actor) {
        UUID actorId = actor.getId();
        JoinDtos.LinkRequest settings = request != null ? request : new JoinDtos.LinkRequest();
        LocalDateTime now = LocalDateTime.now();
        validate(settings, now);

        Mono<Link> creation = findGroup(groupId, actor)
                .then(Mono.defer(() -> joinRepository.lockByGroupId(groupId)
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty())))
                .flatMap(found -> {
                    Link existing = found.orElse(null);
                    boolean requiresApproval = settings.getRequiresApproval() != null
                            ? settings.getRequiresApproval()
                            : existing != null && existing.requiresApproval();
                    LocalDateTime expiresAt = settings.isExpiresAtPresent() || existing == null
                            ? settings.getExpiresAt() : existing.expiresAt();
                    Integer maxUses = settings.isMaxUsesPresent() || existing == null
                            ? settings.getMaxUses() : existing.maxUses();
                    return joinRepository.upsert(groupId, newCode(), requiresApproval, expiresAt, maxUses, actorId, now)
                            .flatMap(link -> auditService.record(actorId,
                                            existing == null ? GroupAuditService.LINK_CREATED : GroupAuditService.LINK_REGENERATED,
                                            groupId, null, describe(link))
                                    .thenReturn(link));
                });

        // A code that is taken already (one chance in 2^50 per link) rolls the transaction back; try another
        return transactionalOperator.transactional(creation)
                .retryWhen(Retry.max(3).filter(DuplicateKeyException.class::isInstance))
                .flatMap(this::toResponse)
                .doOnSuccess(link -> log.info("Join link of group {} created by {}", groupId, actorId));
    }

    /**
     * Change the settings of the link; the code stays. Switching it off is LINK_DISABLED in the audit.
     */
    public Mono<JoinDtos.LinkResponse> updateLink(Long groupId, JoinDtos.LinkRequest request, UserPrincipal actor) {
        UUID actorId = actor.getId();
        LocalDateTime now = LocalDateTime.now();
        validate(request, now);

        Mono<Long> update = findGroup(groupId, actor)
                .then(Mono.defer(() -> joinRepository.lockByGroupId(groupId)))
                .switchIfEmpty(Mono.error(GroupException.joinLinkNotFound()))
                .flatMap(existing -> {
                    boolean enabled = request.getEnabled() != null ? request.getEnabled() : existing.enabled();
                    boolean requiresApproval = request.getRequiresApproval() != null
                            ? request.getRequiresApproval() : existing.requiresApproval();
                    LocalDateTime expiresAt = request.isExpiresAtPresent() ? request.getExpiresAt() : existing.expiresAt();
                    Integer maxUses = request.isMaxUsesPresent() ? request.getMaxUses() : existing.maxUses();
                    String action = existing.enabled() && !enabled ? GroupAuditService.LINK_DISABLED
                            : !existing.enabled() && enabled ? GroupAuditService.LINK_ENABLED
                            : GroupAuditService.LINK_UPDATED;
                    return joinRepository.updateSettings(existing.id(), enabled, requiresApproval, expiresAt, maxUses, now)
                            .flatMap(updated -> auditService.record(actorId, action, groupId, null,
                                            "enabled=" + enabled + " requiresApproval=" + requiresApproval
                                                    + " expiresAt=" + expiresAt + " maxUses=" + maxUses
                                                    + " uses=" + existing.uses())
                                    .thenReturn(updated));
                });

        return transactionalOperator.transactional(update)
                .then(Mono.defer(() -> joinRepository.findByGroupId(groupId)))
                .flatMap(this::toResponse)
                .doOnSuccess(link -> log.info("Join link of group {} updated by {}", groupId, actorId));
    }

    /**
     * One page of the join requests of a group in one status (PENDING by default), oldest first
     */
    public Mono<PageResponse<JoinDtos.Request>> getRequests(Long groupId, String status, int page, int size, UserPrincipal actor) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String wanted = status == null || status.isBlank() ? STATUS_PENDING : status.trim().toUpperCase(Locale.ROOT);
        if (!REQUEST_STATUSES.contains(wanted)) {
            throw new ValidationException("status must be PENDING, APPROVED or REJECTED");
        }
        return findGroup(groupId, actor)
                .then(Mono.defer(() -> joinRepository.countRequests(groupId, wanted)))
                .flatMap(total -> joinRepository.findRequests(groupId, wanted, pageSize, (long) pageNumber * pageSize)
                        .collectList()
                        // One lookup names the whole page
                        .flatMap(rows -> participants.names(rows.stream().map(JoinRequest::userId).toList())
                                .map(users -> PageResponse.of(
                                        rows.stream().map(row -> {
                                            UserRef user = users.get(row.userId());
                                            return JoinDtos.Request.builder()
                                                    .id(row.id())
                                                    .userId(row.userId())
                                                    .participantName(ParticipantDirectory.fullName(user))
                                                    .email(user != null ? user.getEmail() : null)
                                                    .status(row.status())
                                                    .createdAt(row.createdAt())
                                                    .decidedBy(row.decidedBy())
                                                    .decidedAt(row.decidedAt())
                                                    .build();
                                        }).toList(),
                                        pageNumber, pageSize, total))));
    }

    /**
     * Approve or reject requests (up to 500 at once). Requests that are not open are skipped.
     */
    public Mono<JoinDtos.DecisionResult> decide(Long groupId, List<Long> requestIds, boolean approve, UserPrincipal actor) {
        UUID actorId = actor.getId();
        List<Long> ids = List.copyOf(new LinkedHashSet<>(requestIds));
        if (ids.isEmpty() || ids.size() > JoinDtos.MAX_DECISIONS) {
            return Mono.error(new ValidationException("Between 1 and " + JoinDtos.MAX_DECISIONS + " request ids"));
        }
        // One statement decides, adds the members and writes the audit rows; the operator makes it a transaction
        return findGroup(groupId, actor)
                .then(Mono.defer(() -> transactionalOperator.transactional(
                        joinRepository.decide(groupId, ids, approve, actorId, LocalDateTime.now()))))
                .map(decided -> JoinDtos.DecisionResult.builder()
                        .requested(ids.size())
                        .decided(decided)
                        .skipped(ids.size() - decided)
                        .build())
                .doOnSuccess(result -> log.info("Join requests of group {} {} by {}: {} of {}", groupId,
                        approve ? "approved" : "rejected", actorId, result.getDecided(), result.getRequested()));
    }

    /**
     * Decide one request; unlike the bulk form, a request that is not open is a 404
     */
    public Mono<JoinDtos.DecisionResult> decideOne(Long groupId, Long requestId, boolean approve, UserPrincipal actor) {
        return decide(groupId, List.of(requestId), approve, actor)
                .flatMap(result -> result.getDecided() == 1
                        ? Mono.just(result)
                        : Mono.<JoinDtos.DecisionResult>error(GroupException.joinRequestNotFound()));
    }

    // ==================== Participant ====================

    /**
     * What a code is for: the name of the group and whether joining needs approval. Nothing else.
     */
    public Mono<JoinDtos.Info> getInfo(String code) {
        LocalDateTime now = LocalDateTime.now();
        return Mono.justOrEmpty(normalize(code))
                .flatMap(joinRepository::findByCode)
                .filter(link -> link.usableAt(now))
                .switchIfEmpty(Mono.error(GroupException.joinCodeInvalid()))
                .map(link -> JoinDtos.Info.builder()
                        .groupName(link.groupName())
                        .requiresApproval(link.requiresApproval())
                        .build());
    }

    /**
     * Join the group of a code. Only a participant (STUDENT) whose email is verified. Idempotent:
     * joining again answers the state the caller is in and counts no further use.
     */
    public Mono<JoinDtos.JoinResult> join(String code, UUID userId, String role) {
        if (!STUDENT.equals(role)) {
            return Mono.error(GroupException.participantsOnly());
        }
        String normalized = normalize(code);
        if (normalized == null) {
            return Mono.error(GroupException.joinCodeInvalid());
        }
        // The token does not say whether the email is verified: auth-service is asked (an unverified
        // account must not gain group access; an account that no longer exists neither)
        return userDirectory.findById(userId)
                .filter(UserRef::isEmailVerified)
                .switchIfEmpty(Mono.error(GroupException.emailNotVerified()))
                .flatMap(user -> transactionalOperator.transactional(joinLocked(normalized, userId)));
    }

    // ==================== Private Helper Methods ====================

    /**
     * Inside one transaction, holding the lock of the link row
     */
    private Mono<JoinDtos.JoinResult> joinLocked(String code, UUID userId) {
        LocalDateTime now = LocalDateTime.now();
        return joinRepository.lockByCode(code)
                // Switched off or expired: invalid, also for somebody who joined earlier
                .filter(link -> link.enabled() && (link.expiresAt() == null || link.expiresAt().isAfter(now)))
                .switchIfEmpty(Mono.error(GroupException.joinCodeInvalid()))
                .flatMap(link -> Mono.zip(joinRepository.isMember(link.groupId(), userId),
                                joinRepository.hasPendingRequest(link.groupId(), userId))
                        .flatMap(state -> {
                            if (state.getT1()) {
                                return Mono.just(result(STATUS_MEMBER, link, false));
                            }
                            if (state.getT2()) {
                                return Mono.just(result(STATUS_PENDING, link, false));
                            }
                            if (link.maxUses() != null && link.uses() >= link.maxUses()) {
                                return Mono.error(GroupException.joinCodeInvalid());
                            }
                            if (link.requiresApproval()) {
                                return joinRepository.addRequest(link.groupId(), userId, now)
                                        .then(joinRepository.countUse(link.id(), now))
                                        .then(auditService.record(userId, GroupAuditService.JOIN_REQUESTED, link.groupId(),
                                                userId, "requested to join with the link"))
                                        .thenReturn(result(STATUS_PENDING, link, true));
                            }
                            return memberRepository.addMember(link.groupId(), userId, GroupMemberSource.LINK.name(), link.createdBy())
                                    .then(joinRepository.countUse(link.id(), now))
                                    .then(auditService.record(userId, GroupAuditService.JOIN_VIA_LINK, link.groupId(),
                                            userId, "joined with the link"))
                                    .thenReturn(result(STATUS_MEMBER, link, true));
                        }));
    }

    private JoinDtos.JoinResult result(String status, Link link, boolean changed) {
        return JoinDtos.JoinResult.builder().status(status).groupName(link.groupName()).changed(changed).build();
    }

    /**
     * The group, if the actor may manage it (ADMIN every group, INSTRUCTOR the own ones); 404 otherwise
     */
    private Mono<Void> findGroup(Long groupId, UserPrincipal actor) {
        return accessPolicy.findManageableGroup(groupId, actor.getId(), actor.getRole()).then();
    }

    private void validate(JoinDtos.LinkRequest request, LocalDateTime now) {
        if (request == null) {
            throw new ValidationException("A request body is required");
        }
        if (request.getExpiresAt() != null && !request.getExpiresAt().isAfter(now)) {
            throw new ValidationException("expiresAt must be in the future");
        }
    }

    private Mono<JoinDtos.LinkResponse> toResponse(Link link) {
        LocalDateTime now = LocalDateTime.now();
        return joinRepository.countRequests(link.groupId(), STATUS_PENDING)
                .map(pending -> JoinDtos.LinkResponse.builder()
                        .groupId(link.groupId())
                        .code(link.code())
                        .enabled(link.enabled())
                        .requiresApproval(link.requiresApproval())
                        .expiresAt(link.expiresAt())
                        .maxUses(link.maxUses())
                        .uses(link.uses())
                        .pendingRequests(pending)
                        .usable(link.usableAt(now))
                        .createdAt(link.createdAt())
                        .updatedAt(link.updatedAt())
                        .build());
    }

    // The code itself is a secret of the group: it is not written to the audit log
    private String describe(Link link) {
        return "requiresApproval=" + link.requiresApproval() + " expiresAt=" + link.expiresAt()
                + " maxUses=" + link.maxUses();
    }

    private String newCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /**
     * The code as it is stored (upper case), or null when it cannot be one
     */
    private String normalize(String code) {
        if (code == null) {
            return null;
        }
        String value = code.trim().toUpperCase(Locale.ROOT);
        if (value.length() != CODE_LENGTH) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            if (ALPHABET.indexOf(value.charAt(i)) < 0) {
                return null;
            }
        }
        return value;
    }
}
