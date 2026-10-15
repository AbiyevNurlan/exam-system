package com.examportal.exam.service;

import com.examportal.common.domain.dto.group.ParticipantDtos;
import com.examportal.common.domain.dto.internal.ParticipantQuery;
import com.examportal.common.domain.entity.GroupMemberSource;
import com.examportal.common.exception.ValidationException;
import com.examportal.common.utils.LogMaskUtils;
import com.examportal.exam.client.UserDirectoryClient;
import com.examportal.exam.exception.GroupException;
import com.examportal.exam.repository.GroupMembershipRepository;
import com.examportal.common.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Group Bulk Service
 * Adds many participants to a group at once (a filter: ADMIN only; ids: also an INSTRUCTOR to an own group):
 * everybody a filter of the participant list matches, or a list of ids (a page selection).
 *
 * A dry run reports what would happen and writes nothing. A real run by filter must say how many
 * it expects to add; if that is no longer true nothing is written (409), so the administrator
 * never adds people they did not see. More than 20 000 additions in one request are refused (422).
 * The memberships are written in batches of 1000 inside one transaction, together with one audit
 * row; adding is idempotent, so repeating a request adds nobody twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupBulkService {

    private static final int BATCH_SIZE = 1000;

    private final GroupAccessPolicy accessPolicy;
    private final GroupMembershipRepository membershipRepository;
    private final ParticipantService participantService;
    private final UserDirectoryClient userDirectory;
    private final GroupAuditService auditService;
    private final TransactionalOperator transactionalOperator;

    /**
     * ADMIN only (the controller enforces it): a filter reaches every participant of the platform,
     * which only the participant list (ADMIN) may show
     */
    public Mono<ParticipantDtos.BulkResult> addByFilter(Long groupId, ParticipantDtos.BulkByFilterRequest request, UserPrincipal actor) {
        ParticipantDtos.Filter filter = request.getFilter() != null ? request.getFilter() : new ParticipantDtos.Filter();
        if (!request.isDryRun() && request.getExpectedCount() == null) {
            return Mono.error(new ValidationException("expectedCount is required for a real run (take it from the dry run)"));
        }
        return findGroup(groupId, actor)
                .then(Mono.defer(() -> participantService.resolveFilter(filter)))
                .flatMap(resolved -> run(groupId, resolved, request.isDryRun(), request.getExpectedCount(),
                        "filter " + describe(filter), actor.getId()));
    }

    /**
     * ADMIN for every group, INSTRUCTOR for the own ones (GroupAccessPolicy)
     */
    public Mono<ParticipantDtos.BulkResult> addByIds(Long groupId, ParticipantDtos.BulkByIdsRequest request, UserPrincipal actor) {
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(request.getUserIds()));
        // Only participants among the given ids count: an unknown id or a user with another role is left out
        ParticipantQuery.Filter filter = ParticipantQuery.Filter.builder().includeIds(ids).build();
        return findGroup(groupId, actor)
                .then(Mono.defer(() -> run(groupId, filter, request.isDryRun(), null, "ids " + ids.size(), actor.getId())));
    }

    // ==================== Private Helper Methods ====================

    private Mono<Void> findGroup(Long groupId, UserPrincipal actor) {
        return accessPolicy.findManageableGroup(groupId, actor.getId(), actor.getRole()).then();
    }

    private Mono<ParticipantDtos.BulkResult> run(Long groupId, ParticipantQuery.Filter filter, boolean dryRun,
                                                 Integer expectedCount, String what, UUID actorId) {
        return userDirectory.participantIds(ParticipantQuery.IdsRequest.builder()
                        .filter(filter)
                        .limit(ParticipantQuery.MAX_IDS)
                        .build())
                .flatMap(found -> {
                    List<UUID> ids = found.getIds() != null ? found.getIds() : List.<UUID>of();
                    long matched = found.getTotal();
                    if (matched > ids.size()) {
                        // More matches than one answer carries: far above the cap in any case
                        return Mono.error(GroupException.tooManyMatches(ParticipantDtos.BULK_CAP));
                    }
                    Mono<Long> already = ids.isEmpty() ? Mono.just(0L) : membershipRepository.countMembersAmong(groupId, ids);
                    return already.flatMap(alreadyMembers -> {
                        long willAdd = matched - alreadyMembers;
                        ParticipantDtos.BulkResult.BulkResultBuilder result = ParticipantDtos.BulkResult.builder()
                                .dryRun(dryRun)
                                .matched(matched)
                                .alreadyMembers(alreadyMembers)
                                .willAdd(willAdd)
                                .cap(ParticipantDtos.BULK_CAP);
                        if (dryRun) {
                            return Mono.just(result.build());
                        }
                        if (willAdd > ParticipantDtos.BULK_CAP) {
                            return Mono.error(GroupException.tooManyMatches(ParticipantDtos.BULK_CAP));
                        }
                        if (expectedCount != null && willAdd != expectedCount) {
                            return Mono.error(GroupException.filterCountChanged(matched, alreadyMembers, willAdd));
                        }
                        return transactionalOperator.transactional(write(groupId, ids, what, matched, actorId))
                                .map(added -> result.added(added).skipped(matched - added).build())
                                .doOnSuccess(done -> log.info("Bulk addition to group {} by {}: {} matched, {} added",
                                        groupId, actorId, matched, done.getAdded()));
                    });
                });
    }

    /**
     * The memberships in batches and the one audit row; part of one transaction
     */
    private Mono<Long> write(Long groupId, List<UUID> ids, String what, long matched, UUID actorId) {
        List<List<UUID>> batches = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += BATCH_SIZE) {
            batches.add(ids.subList(from, Math.min(ids.size(), from + BATCH_SIZE)));
        }
        return Flux.fromIterable(batches)
                // Sequential: these writes share the one connection of the transaction
                .concatMap(batch -> membershipRepository.addMembers(groupId, batch, GroupMemberSource.BULK.name(), actorId))
                .reduce(0L, Long::sum)
                .flatMap(added -> auditService.record(actorId, GroupAuditService.MEMBERS_BULK_ADDED, groupId, null,
                                what + " matched=" + matched + " added=" + added + " skipped=" + (matched - added))
                        .thenReturn(added));
    }

    /**
     * The filter in a few words for the audit row; a search text that looks like an email is masked
     */
    private String describe(ParticipantDtos.Filter filter) {
        List<String> parts = new ArrayList<>();
        if (filter.getQ() != null && !filter.getQ().isBlank()) {
            String q = filter.getQ().trim();
            parts.add("q=" + (q.contains("@") ? LogMaskUtils.maskEmail(q) : q));
        }
        if (filter.getGroupId() != null) {
            parts.add("groupId=" + filter.getGroupId());
        }
        if (Boolean.TRUE.equals(filter.getNoGroup())) {
            parts.add("noGroup");
        }
        if (filter.getVerified() != null) {
            parts.add("verified=" + filter.getVerified());
        }
        if (filter.getBlocked() != null) {
            parts.add("blocked=" + filter.getBlocked());
        }
        if (filter.getRegisteredFrom() != null || filter.getRegisteredTo() != null) {
            parts.add("registered=" + filter.getRegisteredFrom() + ".." + filter.getRegisteredTo());
        }
        return parts.isEmpty() ? "(everybody)" : String.join(" ", parts);
    }
}
