package com.examportal.exam.service;

import com.examportal.common.domain.dto.AuditEntryResponse;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.internal.AdminAuditQuery;
import com.examportal.common.domain.dto.internal.UserLookupResponse.UserRef;
import com.examportal.common.exception.ValidationException;
import com.examportal.exam.client.UserDirectoryClient;
import com.examportal.exam.repository.AuditQueryRepository;
import com.examportal.exam.repository.AuditQueryRepository.Filter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Audit Service
 * One audit list for administrators over the two audit tables of the system: group_audit_log of
 * exam-service (groups, memberships, join links, exams, grading, result exports) and admin_audit_log
 * of auth-service (unlocking accounts, verifying emails by hand). The tables live in two databases,
 * so each is asked for its count and its newest rows up to the end of the requested page, and the two
 * lists are merged here by time. Deep pages are refused (MAX_DEPTH) to keep that bounded.
 *
 * Neither table holds join codes, OTP codes or passwords; details are cleaned once more anyway.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    public static final String EXAM_SERVICE = "EXAM_SERVICE";
    public static final String AUTH_SERVICE = "AUTH_SERVICE";

    private static final int MAX_PAGE_SIZE = 50;
    // page * size + size may not go beyond this many rows of the merged list
    static final int MAX_DEPTH = 10_000;
    private static final Pattern ACTION = Pattern.compile("^[A-Z_]{2,50}$");
    private static final Pattern EXAM_ID = Pattern.compile("\\bexamId=(\\d+)");
    // key=value pairs that must never be shown, whatever wrote them
    private static final Pattern SECRET = Pattern.compile("(?i)\\b(code|otp|token|password|secret)=\\S+");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private final AuditQueryRepository auditRepository;
    private final UserDirectoryClient userDirectory;
    private final ParticipantDirectory participants;

    /**
     * The audit list for the caller. ADMIN: both logs, every actor. B13, INSTRUCTOR: only the teacher's own actions
     * in exam-service (groups, members, invites, join links, exams, grading, result exports of the teacher); an
     * actorId other than the teacher or source AUTH_SERVICE gives an empty page (the admin log of auth-service is
     * never shown to a teacher), and any email in the details is masked once more. No row carries an ip address,
     * a user agent, a join code or an OTP (SECRET).
     */
    public Mono<PageResponse<AuditEntryResponse>> list(String action, String actorId, String from, String to, String q,
                                                       String source, int page, int size, UUID callerId, String callerRole) {
        UUID owner = OwnershipPolicy.ownerFilter(callerId, callerRole);
        if (owner == null) {
            return list(action, actorId, from, to, q, source, page, size);
        }
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        UUID requested = parseUuid(actorId);
        String sourceFilter = source == null || source.isBlank() ? EXAM_SERVICE : source.trim().toUpperCase(Locale.ROOT);
        if ((requested != null && !requested.equals(owner)) || AUTH_SERVICE.equals(sourceFilter)) {
            return Mono.just(PageResponse.of(List.of(), pageNumber, pageSize, 0));
        }
        return list(action, owner.toString(), from, to, q, EXAM_SERVICE, page, size)
                .map(found -> PageResponse.of(found.getContent().stream()
                                .map(row -> row.toBuilder().details(maskEmails(row.getDetails())).build())
                                .toList(),
                        found.getPage(), found.getSize(), found.getTotalElements()));
    }

    // Any email address in a text, masked as in the logs (a***@x.test)
    static String maskEmails(String details) {
        return details == null ? null : EMAIL.matcher(details).replaceAll(match -> Matcher.quoteReplacement(
                com.examportal.common.utils.LogMaskUtils.maskEmail(match.group())));
    }

    public Mono<PageResponse<AuditEntryResponse>> list(String action, String actorId, String from, String to, String q,
                                                       String source, int page, int size) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int depth = (pageNumber + 1) * pageSize;
        if (depth > MAX_DEPTH) {
            throw new ValidationException("The audit list can be paged up to " + MAX_DEPTH + " rows; narrow it with a filter");
        }
        String actionFilter = action == null || action.isBlank() ? null : action.trim().toUpperCase(Locale.ROOT);
        if (actionFilter != null && !ACTION.matcher(actionFilter).matches()) {
            throw new ValidationException("action must be an action name such as MEMBER_ADDED");
        }
        UUID actor = parseUuid(actorId);
        LocalDateTime fromTime = parseTime("from", from);
        LocalDateTime toTime = parseTime("to", to);
        if (fromTime != null && toTime != null && !toTime.isAfter(fromTime)) {
            throw new ValidationException("to must be after from");
        }
        String term = participants.searchTerm(q);
        String sourceFilter = source == null || source.isBlank() ? null : source.trim().toUpperCase(Locale.ROOT);
        if (sourceFilter != null && !sourceFilter.equals(EXAM_SERVICE) && !sourceFilter.equals(AUTH_SERVICE)) {
            throw new ValidationException("source must be EXAM_SERVICE or AUTH_SERVICE");
        }

        Filter filter = new Filter(actionFilter, actor, fromTime, toTime, term);
        Mono<List<AuditEntryResponse>> examRows = AUTH_SERVICE.equals(sourceFilter)
                ? Mono.just(List.of())
                : auditRepository.findNewest(filter, depth).map(this::fromExamService).collectList();
        Mono<Long> examTotal = AUTH_SERVICE.equals(sourceFilter) ? Mono.just(0L) : auditRepository.count(filter);
        Mono<AdminAuditQuery.Response> authRows = EXAM_SERVICE.equals(sourceFilter)
                ? Mono.just(AdminAuditQuery.Response.builder().total(0).entries(List.of()).build())
                : userDirectory.adminAudit(AdminAuditQuery.Request.builder()
                        .action(actionFilter).actorId(actor).from(fromTime).to(toTime).q(term).limit(depth).build());

        return Mono.zip(examRows, examTotal, authRows)
                .flatMap(found -> {
                    List<AuditEntryResponse> merged = Stream.concat(found.getT1().stream(),
                                    (found.getT3().getEntries() == null ? List.<AdminAuditQuery.Entry>of() : found.getT3().getEntries())
                                            .stream().map(this::fromAuthService))
                            .sorted(Comparator.comparing(AuditEntryResponse::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                                    .thenComparing(AuditEntryResponse::getId, Comparator.reverseOrder()))
                            .skip((long) pageNumber * pageSize)
                            .limit(pageSize)
                            .collect(Collectors.toCollection(ArrayList::new));
                    long total = found.getT2() + found.getT3().getTotal();
                    return named(merged).map(rows -> PageResponse.of(rows, pageNumber, pageSize, total));
                });
    }

    /**
     * B11, the participant card: the given rows of both audit logs (each already limited to the newest of
     * one user), merged by time, the newest `limit`, with actor and target names and codes from one lookup
     */
    public Mono<List<AuditEntryResponse>> merge(List<AuditQueryRepository.Row> examRows, List<AdminAuditQuery.Entry> authRows,
                                                int limit) {
        List<AuditEntryResponse> merged = Stream.concat(
                        (examRows == null ? List.<AuditQueryRepository.Row>of() : examRows).stream().map(this::fromExamService),
                        (authRows == null ? List.<AdminAuditQuery.Entry>of() : authRows).stream().map(this::fromAuthService))
                .sorted(Comparator.comparing(AuditEntryResponse::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(AuditEntryResponse::getId, Comparator.reverseOrder()))
                .limit(limit)
                .collect(Collectors.toCollection(ArrayList::new));
        return named(merged);
    }

    /**
     * Actor and target names of one page: one lookup for all of them (no names when it fails)
     */
    private Mono<List<AuditEntryResponse>> named(List<AuditEntryResponse> rows) {
        Set<UUID> ids = rows.stream()
                .flatMap(row -> Stream.of(row.getActorId(), row.getTargetUserId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return participants.names(ids).map(users -> {
            for (AuditEntryResponse row : rows) {
                row.setActorName(name(users, row.getActorId()));
                row.setActorCode(code(users, row.getActorId()));
                row.setTargetUserName(name(users, row.getTargetUserId()));
                row.setTargetUserCode(code(users, row.getTargetUserId()));
            }
            return rows;
        });
    }

    private String name(Map<UUID, UserRef> users, UUID id) {
        return id == null ? null : ParticipantDirectory.fullName(users.get(id));
    }

    // The participant code is shown next to the name; display only
    private Integer code(Map<UUID, UserRef> users, UUID id) {
        UserRef user = id == null ? null : users.get(id);
        return user == null ? null : user.getParticipantCode();
    }

    private AuditEntryResponse fromExamService(AuditQueryRepository.Row row) {
        String targetType;
        String targetId;
        Matcher exam = EXAM_ID.matcher(row.details() == null ? "" : row.details());
        if ((row.action().startsWith("EXAM_") || row.action().startsWith("ANSWER_") || row.action().startsWith("RESULTS_"))
                && exam.find()) {
            targetType = "EXAM";
            targetId = exam.group(1);
        } else if (row.groupId() != null) {
            targetType = "GROUP";
            targetId = String.valueOf(row.groupId());
        } else if (row.targetUserId() != null) {
            targetType = "USER";
            targetId = row.targetUserId().toString();
        } else {
            targetType = null;
            targetId = null;
        }
        return AuditEntryResponse.builder()
                .id("G-" + row.id())
                .source(EXAM_SERVICE)
                .action(row.action())
                .actorId(row.actorId())
                .targetType(targetType)
                .targetId(targetId)
                .targetUserId(row.targetUserId())
                .details(clean(row.details()))
                .createdAt(row.createdAt())
                .build();
    }

    private AuditEntryResponse fromAuthService(AdminAuditQuery.Entry entry) {
        return AuditEntryResponse.builder()
                .id("A-" + entry.getId())
                .source(AUTH_SERVICE)
                .action(entry.getAction())
                .actorId(entry.getActorId())
                .targetType(entry.getTargetUserId() != null ? "USER" : null)
                .targetId(entry.getTargetUserId() != null ? entry.getTargetUserId().toString() : null)
                .targetUserId(entry.getTargetUserId())
                // B11: profile changes carry "field: before -> after"; cleaned like the details of exam-service
                .details(clean(entry.getDetails()))
                .createdAt(entry.getCreatedAt())
                .build();
    }

    /**
     * Details as stored, with any key=value pair of a secret kind masked (none is written today)
     */
    static String clean(String details) {
        return details == null ? null : SECRET.matcher(details).replaceAll("$1=***");
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("actorId must be a user id");
        }
    }

    /**
     * An ISO date-time; with an offset (2026-10-14T10:00:00Z, +04:00) it is converted to UTC,
     * without one it is taken as UTC
     */
    private LocalDateTime parseTime(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            return OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException withoutOffset) {
            try {
                return LocalDateTime.parse(text);
            } catch (DateTimeParseException e) {
                throw new ValidationException(name + " must be an ISO date-time such as 2026-10-14T10:00:00Z");
            }
        }
    }
}
