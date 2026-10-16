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
     * One page of participants. sort: createdAt (default, newest first), name or email.
     */
    public Mono<PageResponse<ParticipantDtos.Participant>> getParticipants(ParticipantDtos.Filter filter, String sort,
                                                                           String order, int page, int size) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), ParticipantQuery.MAX_PAGE_SIZE);
        String sortBy = sort == null || sort.isBlank() ? "createdAt" : sort.trim();
        if (!List.of("createdat", "name", "email").contains(sortBy.toLowerCase(Locale.ROOT))) {
            throw new ValidationException("sort must be createdAt, name or email");
        }
        String direction = order == null || order.isBlank() ? null : order.trim().toLowerCase(Locale.ROOT);
        if (direction != null && !direction.equals("asc") && !direction.equals("desc")) {
            throw new ValidationException("order must be asc or desc");
        }
        // The newest registrations first; names and emails from A
        boolean ascending = direction != null ? direction.equals("asc") : !sortBy.equalsIgnoreCase("createdAt");

        return resolveFilter(filter)
                .flatMap(resolved -> userDirectory.participants(ParticipantQuery.PageRequest.builder()
                        .filter(resolved)
                        .page(pageNumber)
                        .size(pageSize)
                        .sort(sortBy)
                        .ascending(ascending)
                        .build()))
                .flatMap(found -> {
                    List<UUID> ids = found.getUsers().stream().map(ParticipantQuery.Participant::getId).toList();
                    // The groups of the whole page in one statement
                    Mono<Map<UUID, List<UserGroupName>>> groups = ids.isEmpty()
                            ? Mono.just(Map.of())
                            : membershipRepository.findGroupsOfUsers(ids).collectList()
                                    .map(rows -> rows.stream().collect(Collectors.groupingBy(UserGroupName::userId)));
                    return groups.map(byUser -> PageResponse.of(
                            found.getUsers().stream().map(user -> toParticipant(user, byUser.getOrDefault(user.getId(), List.of()))).toList(),
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

    // ==================== Private Helper Methods ====================

    private LocalDate day(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value.trim());
    }

    private ParticipantDtos.Participant toParticipant(ParticipantQuery.Participant user, List<UserGroupName> groups) {
        return ParticipantDtos.Participant.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .participantCode(user.getParticipantCode())
                .registeredAt(user.getCreatedAt())
                .emailVerified(user.isEmailVerified())
                .blocked(user.isBlocked())
                .lockedUntil(user.getLockedUntil())
                .groups(groups.stream()
                        .limit(GROUPS_SHOWN)
                        .map(group -> new ParticipantDtos.GroupRef(group.groupId(), group.groupName()))
                        .toList())
                .groupCount(groups.size())
                .build();
    }
}
