package com.examportal.exam.service;

import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.group.ParticipantDtos;
import com.examportal.common.domain.dto.internal.ParticipantQuery;
import com.examportal.common.exception.ValidationException;
import com.examportal.exam.client.UserDirectoryClient;
import com.examportal.exam.exception.GroupException;
import com.examportal.exam.repository.GroupMembershipRepository;
import com.examportal.exam.repository.GroupMembershipRepository.UserGroupName;
import com.examportal.exam.repository.UserGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Participant Service
 * The admin's list of participants (users with the role STUDENT), with the groups each is in.
 *
 * Users live in auth-service and memberships here, so no single statement can filter by both.
 * The list is filtered, sorted and paged in auth-service's database; a group filter is handed
 * over as the set of member ids (taken from this database with one statement), and the group
 * names of the 25 rows of a page come from one more statement here. Neither service loads the
 * user table, and the browser only ever gets one page.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipantService {

    private static final int GROUPS_SHOWN = 3;

    private final UserDirectoryClient userDirectory;
    private final GroupMembershipRepository membershipRepository;
    private final UserGroupRepository groupRepository;
    private final ExamDayClock dayClock;

    /**
     * One page of participants, sorted on the server (the list is paged, so only the server can sort it).
     * sortBy: code, firstName, lastName, email or registeredAt (a whitelist; anything else is 400), dir: asc
     * (default) or desc. Without sortBy the older parameters apply as before: sort createdAt (default, newest
     * first), name or email, order asc or desc.
     */
    public Mono<PageResponse<ParticipantDtos.Participant>> getParticipants(ParticipantDtos.Filter filter, String sortBy,
                                                                           String dir, String sort, String order,
                                                                           int page, int size) {
        return getParticipants(filter, sortBy, dir, sort, order, page, size, null, OwnershipPolicy.ADMIN);
    }

    /**
     * The list for the caller. ADMIN: every participant, every filter. B13, INSTRUCTOR: only the members of the
     * own groups (anybody else is not in the list, also not through q or a 7-digit code); groupId only an own group
     * (another owner's group is the same 404 GROUP_NOT_FOUND as a missing one); the profile filters (category,
     * institution), blocked and noGroup are 400, since a teacher does not see those fields; each row shows only the
     * teacher's own groups, and blocked / lockedUntil are not shown (false / null). A teacher without members gets an
     * empty page (200).
     */
    public Mono<PageResponse<ParticipantDtos.Participant>> getParticipants(ParticipantDtos.Filter filter, String sortBy,
                                                                           String dir, String sort, String order,
                                                                           int page, int size, UUID callerId, String callerRole) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), ParticipantQuery.MAX_PAGE_SIZE);
        SortChoice choice = sortChoice(sortBy, dir, sort, order);
        UUID owner = OwnershipPolicy.ownerFilter(callerId, callerRole);
        if (owner != null) {
            return instructorFilter(filter, owner).flatMap(resolved -> resolved.getIncludeIds().isEmpty()
                    ? Mono.just(PageResponse.<ParticipantDtos.Participant>of(List.of(), pageNumber, pageSize, 0))
                    : page(resolved, choice, pageNumber, pageSize, owner));
        }
        return resolveFilter(filter).flatMap(resolved -> page(resolved, choice, pageNumber, pageSize, null));
    }

    /**
     * The filter as the caller may use it: ADMIN the whole platform (resolveFilter), an INSTRUCTOR only the members
     * of the own groups (B13), anybody else nobody
     */
    public Mono<ParticipantQuery.Filter> resolveFilterFor(ParticipantDtos.Filter filter, UUID callerId, String callerRole) {
        UUID owner = OwnershipPolicy.ownerFilter(callerId, callerRole);
        return owner == null ? resolveFilter(filter) : instructorFilter(filter, owner);
    }

    /**
     * B13: the members of the own groups among the given ids (a teacher may only use people of the own groups)
     */
    public Mono<List<UUID>> ownMembersAmong(List<UUID> ids, UUID callerId, String callerRole) {
        UUID owner = OwnershipPolicy.ownerFilter(callerId, callerRole);
        if (owner == null) {
            return Mono.just(ids);
        }
        return membershipRepository.findMemberIdsOfOwner(owner)
                .map(members -> {
                    java.util.Set<UUID> own = new java.util.HashSet<>(members);
                    return ids.stream().filter(own::contains).toList();
                });
    }

    /**
     * The filter of a teacher: the members of the own groups (or of one own group), never more
     */
    private Mono<ParticipantQuery.Filter> instructorFilter(ParticipantDtos.Filter filter, UUID owner) {
        ParticipantDtos.Filter given = filter != null ? filter : new ParticipantDtos.Filter();
        if (given.getCategory() != null && !given.getCategory().isBlank()) {
            return Mono.error(new ValidationException("category", "The profile is not shown to instructors, so it cannot be filtered"));
        }
        if (given.getInstitution() != null && !given.getInstitution().isBlank()) {
            return Mono.error(new ValidationException("institution", "The profile is not shown to instructors, so it cannot be filtered"));
        }
        if (given.getBlocked() != null) {
            return Mono.error(new ValidationException("blocked", "The lock state of an account is not shown to instructors"));
        }
        if (Boolean.TRUE.equals(given.getNoGroup())) {
            return Mono.error(new ValidationException("noGroup", "An instructor sees only the members of the own groups"));
        }
        Long groupId = given.getGroupId();
        ParticipantDtos.Filter withoutGroup = given.toBuilder().groupId(null).build();
        Mono<List<UUID>> members = groupId == null
                ? membershipRepository.findMemberIdsOfOwner(owner)
                : groupRepository.findActiveById(groupId)
                        .filter(group -> owner.equals(group.getOwnerId()))
                        .switchIfEmpty(Mono.error(GroupException.notFound(groupId)))
                        .flatMap(group -> membershipRepository.findMemberIds(group.getId()));
        return resolveFilter(withoutGroup).zipWith(members, (resolved, ids) -> resolved.toBuilder().includeIds(ids).build());
    }

    private Mono<PageResponse<ParticipantDtos.Participant>> page(ParticipantQuery.Filter resolved, SortChoice choice,
                                                                 int pageNumber, int pageSize, UUID owner) {
        return userDirectory.participants(ParticipantQuery.PageRequest.builder()
                        .filter(resolved)
                        .page(pageNumber)
                        .size(pageSize)
                        .sort(choice.key())
                        .ascending(choice.ascending())
                        .build())
                .flatMap(found -> {
                    List<UUID> ids = found.getUsers().stream().map(ParticipantQuery.Participant::getId).toList();
                    // The groups of the whole page in one statement (a teacher: only the own groups)
                    Mono<Map<UUID, List<UserGroupName>>> groups = ids.isEmpty()
                            ? Mono.just(Map.of())
                            : membershipRepository.findGroupsOfUsers(ids, owner).collectList()
                                    .map(rows -> rows.stream().collect(Collectors.groupingBy(UserGroupName::userId)));
                    return groups.map(byUser -> PageResponse.of(
                            found.getUsers().stream().map(user -> toParticipant(user, byUser.getOrDefault(user.getId(), List.of()), owner == null)).toList(),
                            pageNumber, pageSize, found.getTotal()));
                });
    }

    /**
     * Counts for the top of the list
     */
    public Mono<ParticipantDtos.Summary> getSummary() {
        LocalDateTime now = LocalDateTime.now();
        return membershipRepository.findAllMemberIds()
                .flatMap(memberIds -> userDirectory.participantSummary(ParticipantQuery.SummaryRequest.builder()
                        .todayFrom(dayClock.today().start())
                        .memberIds(memberIds)
                        .build()))
                .map(summary -> ParticipantDtos.Summary.builder()
                        .total(summary.getTotal())
                        .registeredToday(summary.getRegisteredToday())
                        .unverified(summary.getUnverified())
                        .noGroup(summary.getNoGroup())
                        .temporarilyBlocked(summary.getTemporarilyBlocked())
                        .blocked(summary.getBlocked())
                        .generatedAt(now)
                        .build());
    }

    /**
     * The filter as auth-service takes it: days become UTC moments, and a group filter becomes the
     * set of ids to keep (members of the group) or to leave out (members of any group).
     * Anything wrong with the filter is a 400; an unknown group is a 404.
     */
    public Mono<ParticipantQuery.Filter> resolveFilter(ParticipantDtos.Filter filter) {
        ParticipantDtos.Filter given = filter != null ? filter : new ParticipantDtos.Filter();
        boolean noGroup = Boolean.TRUE.equals(given.getNoGroup());
        if (noGroup && given.getGroupId() != null) {
            return Mono.error(new ValidationException("groupId and noGroup cannot be combined"));
        }
        String term = given.getQ() == null || given.getQ().isBlank() ? null : given.getQ().trim();
        if (term != null && (term.length() < ParticipantQuery.MIN_SEARCH_LENGTH || term.length() > ParticipantQuery.MAX_SEARCH_LENGTH)) {
            return Mono.error(new ValidationException("Search text must be between " + ParticipantQuery.MIN_SEARCH_LENGTH
                    + " and " + ParticipantQuery.MAX_SEARCH_LENGTH + " characters"));
        }
        LocalDate from;
        LocalDate to;
        try {
            from = day(given.getRegisteredFrom());
            to = day(given.getRegisteredTo());
        } catch (DateTimeParseException e) {
            return Mono.error(new ValidationException("registeredFrom and registeredTo must be dates like 2026-10-13"));
        }
        if (from != null && to != null && to.isBefore(from)) {
            return Mono.error(new ValidationException("registeredTo cannot be before registeredFrom"));
        }

        String category = given.getCategory() == null || given.getCategory().isBlank() ? null : given.getCategory().trim().toUpperCase(Locale.ROOT);
        if (category != null && !category.matches("[A-Z][A-Z_]{1,39}")) {
            return Mono.error(new ValidationException("category must be a category code such as UNIVERSITY_STUDENT"));
        }
        String institution = given.getInstitution() == null || given.getInstitution().isBlank() ? null : given.getInstitution().trim();
        if (institution != null && (institution.length() < ParticipantQuery.MIN_SEARCH_LENGTH || institution.length() > ParticipantQuery.MAX_SEARCH_LENGTH)) {
            return Mono.error(new ValidationException("institution must be between " + ParticipantQuery.MIN_SEARCH_LENGTH
                    + " and " + ParticipantQuery.MAX_SEARCH_LENGTH + " characters"));
        }

        ParticipantQuery.Filter.FilterBuilder resolved = ParticipantQuery.Filter.builder()
                .q(term)
                .category(category)
                .institution(institution)
                .verified(given.getVerified())
                .blocked(given.getBlocked())
                // The day is a calendar day in the exam day zone; both ends are inclusive
                .registeredFrom(from != null ? dayClock.startOfDay(from) : null)
                .registeredTo(to != null ? dayClock.startOfDay(to.plusDays(1)) : null);

        if (given.getGroupId() != null) {
            return groupRepository.findActiveById(given.getGroupId())
                    .switchIfEmpty(Mono.error(GroupException.notFound(given.getGroupId())))
                    .flatMap(group -> membershipRepository.findMemberIds(group.getId()))
                    .map(memberIds -> resolved.includeIds(memberIds).build());
        }
        if (noGroup) {
            return membershipRepository.findAllMemberIds().map(memberIds -> resolved.excludeIds(memberIds).build());
        }
        return Mono.just(resolved.build());
    }

    /**
     * The sort key handed to auth-service (ParticipantQuery.PageRequest.sort) and its direction
     */
    record SortChoice(String key, boolean ascending) {
    }

    // sortBy as the API takes it -> the key of ParticipantQueryRepository; nothing else reaches SQL
    private static final Map<String, String> SORT_KEYS = Map.of(
            "code", "code",
            "firstname", "firstName",
            "lastname", "lastName",
            "email", "email",
            "registeredat", "registeredAt");

    /**
     * sortBy and dir (B11b) when sortBy is given, otherwise the older sort and order exactly as before
     * (createdAt newest first by default; name and email from A). A value outside the whitelist is 400.
     */
    static SortChoice sortChoice(String sortBy, String dir, String sort, String order) {
        if (sortBy != null && !sortBy.isBlank()) {
            String key = SORT_KEYS.get(sortBy.trim().toLowerCase(Locale.ROOT));
            if (key == null) {
                throw new ValidationException("sortBy", "must be code, firstName, lastName, email or registeredAt");
            }
            String direction = direction("dir", dir != null && !dir.isBlank() ? dir : order);
            return new SortChoice(key, direction == null || direction.equals("asc"));
        }
        String legacy = sort == null || sort.isBlank() ? "createdAt" : sort.trim();
        if (!List.of("createdat", "name", "email").contains(legacy.toLowerCase(Locale.ROOT))) {
            throw new ValidationException("sort must be createdAt, name or email");
        }
        String direction = direction("order", dir != null && !dir.isBlank() ? dir : order);
        // The newest registrations first; names and emails from A
        boolean ascending = direction != null ? direction.equals("asc") : !legacy.equalsIgnoreCase("createdAt");
        return new SortChoice(legacy, ascending);
    }

    private static String direction(String field, String value) {
        String direction = value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
        if (direction != null && !direction.equals("asc") && !direction.equals("desc")) {
            throw new ValidationException(field, "must be asc or desc");
        }
        return direction;
    }

    // ==================== Private Helper Methods ====================

    private LocalDate day(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value.trim());
    }

    // admin = false: a teacher's row, without the lock state of the account (B13)
    private ParticipantDtos.Participant toParticipant(ParticipantQuery.Participant user, List<UserGroupName> groups, boolean admin) {
        return ParticipantDtos.Participant.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .participantCode(user.getParticipantCode())
                .registeredAt(user.getCreatedAt())
                .emailVerified(user.isEmailVerified())
                .blocked(admin && user.isBlocked())
                .lockedUntil(admin ? user.getLockedUntil() : null)
                .groups(groups.stream()
                        .limit(GROUPS_SHOWN)
                        .map(group -> new ParticipantDtos.GroupRef(group.groupId(), group.groupName()))
                        .toList())
                .groupCount(groups.size())
                .build();
    }
}
