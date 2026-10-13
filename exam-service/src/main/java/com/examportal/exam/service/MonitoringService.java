package com.examportal.exam.service;

import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.internal.UserLookupResponse.UserRef;
import com.examportal.common.domain.dto.monitoring.ActiveExamResponse;
import com.examportal.common.domain.dto.monitoring.ExamMonitoringResponse;
import com.examportal.common.domain.dto.monitoring.ViolationResponse;
import com.examportal.common.domain.entity.Exam;
import com.examportal.common.domain.entity.RiskLevel;
import com.examportal.common.exception.ValidationException;
import com.examportal.exam.exception.ExamException;
import com.examportal.exam.repository.ExamSessionRepository;
import com.examportal.exam.repository.MonitoringRepository;
import com.examportal.exam.repository.MonitoringRepository.Filter;
import com.examportal.exam.repository.MonitoringRepository.LiveSession;
import com.examportal.exam.repository.MonitoringRepository.Sort;
import com.examportal.exam.repository.MonitoringRepository.Summary;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Monitoring Service
 * Live monitoring for ADMIN (every exam) and INSTRUCTOR (the own exams): which exams are being
 * taken right now, the running sessions of one exam with their fraud figures, and the fraud events
 * of one session. Read only: nothing here stops a session, warns a participant or changes a score.
 *
 * The two lists are polled by the screen, so they are cached for a few seconds (ReadCache). The
 * access check always runs first and is never cached; the cache key carries whose view it is.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitoringService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_ACTIVE_EXAMS = 200;
    private static final int MAX_VIOLATIONS = 200;
    // Sorting by name happens here, not in SQL (names live in auth-service): only up to this many rows
    private static final int MAX_NAME_SORT_ROWS = 10_000;
    private static final Duration CACHE_TTL = Duration.ofSeconds(3);
    private static final String ADMIN = "ADMIN";

    private final MonitoringRepository monitoringRepository;
    private final ExamSessionRepository sessionRepository;
    private final ExamAccessPolicy accessPolicy;
    private final ParticipantDirectory participants;
    private final FraudRiskPolicy riskPolicy;
    private final ReadCache cache;

    /**
     * What a list request asks for; null = not given
     */
    public record ListQuery(String q, String riskLevel, String sort, String order) {
    }

    /**
     * The exams with at least one running session, the riskiest first
     */
    public Mono<List<ActiveExamResponse>> getActiveExams(UUID userId, String role) {
        UUID ownerId = ADMIN.equals(role) ? null : userId;
        return cache.get("monitoring:active:" + (ownerId == null ? "all" : ownerId), CACHE_TTL,
                new TypeReference<List<ActiveExamResponse>>() {
                },
                () -> monitoringRepository.findActiveExams(ownerId, LocalDateTime.now(), riskScoreSql(), levelRankSql(),
                                MAX_ACTIVE_EXAMS)
                        .map(exam -> ActiveExamResponse.builder()
                                .examId(exam.examId())
                                .title(exam.title())
                                .startTime(exam.startTime())
                                .endTime(exam.endTime())
                                .inProgressCount(exam.inProgressCount())
                                .violationCount(exam.violationCount())
                                .highestRiskLevel(highest(exam.countsByLevel()))
                                .riskCounts(riskCounts(exam.countsByLevel()))
                                .lastEventAt(exam.lastEventAt())
                                .build())
                        .collectList());
    }

    /**
     * The running sessions of one exam. sort: risk (default), violations, startedAt, name, progress.
     */
    public Mono<ExamMonitoringResponse> getExamMonitoring(Long examId, ListQuery query, int page, int size,
                                                          UUID userId, String role) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String term = participants.searchTerm(query.q());
        String sort = query.sort() == null || query.sort().isBlank() ? "risk" : query.sort().trim().toLowerCase(Locale.ROOT);
        if (!Set.of("risk", "violations", "startedat", "name", "progress").contains(sort)) {
            throw new ValidationException("sort must be risk, violations, startedAt, name or progress");
        }
        String order = query.order() == null || query.order().isBlank() ? null : query.order().trim().toLowerCase(Locale.ROOT);
        if (order != null && !order.equals("asc") && !order.equals("desc")) {
            throw new ValidationException("order must be asc or desc");
        }
        boolean ascending = order != null ? order.equals("asc") : sort.equals("name");
        Integer levelRank = levelRank(query.riskLevel());

        // The key says whose view it is (an INSTRUCTOR is never served what was cached for someone
        // else) and spells out every parameter: a hash could make two different requests share an answer
        String key = "monitoring:exam:" + examId + ":" + (ADMIN.equals(role) ? "admin" : userId)
                + ":level=" + (levelRank == null ? "any" : levelRank)
                + ":sort=" + sort + (ascending ? ":asc" : ":desc")
                + ":page=" + pageNumber + ":size=" + pageSize
                + ":q=" + (term == null ? "" : term.toLowerCase(Locale.ROOT));

        return accessPolicy.findManageableExam(examId, userId, role)
                .flatMap(exam -> cache.get(key, CACHE_TTL, new TypeReference<ExamMonitoringResponse>() {
                }, () -> load(exam, term, levelRank, sort, ascending, pageNumber, pageSize)));
    }

    /**
     * The fraud events of one session after sinceId, in id order. Works for a finished session too.
     */
    public Mono<List<ViolationResponse>> getViolations(String sessionId, Long sinceId, int limit, UUID userId, String role) {
        int rows = Math.min(Math.max(limit, 1), MAX_VIOLATIONS);
        long after = sinceId == null ? 0 : Math.max(sinceId, 0);
        return sessionRepository.findBySessionId(sessionId)
                .switchIfEmpty(Mono.error(ExamException.sessionNotFound(sessionId)))
                .flatMap(session -> accessPolicy.findManageableExam(session.getExamId(), userId, role)
                        .thenReturn(session))
                .flatMap(session -> monitoringRepository.findViolations(session.getId(), after, rows)
                        .map(event -> ViolationResponse.builder()
                                .id(event.id())
                                .type(event.type())
                                .occurredAt(event.occurredAt())
                                .details(event.details())
                                .build())
                        .collectList());
    }

    // ==================== Private Helper Methods ====================

    private record Search(UUID[] userIds, Map<UUID, UserRef> users) {
    }

    private Mono<ExamMonitoringResponse> load(Exam exam, String term, Integer levelRank, String sort, boolean ascending,
                                              int pageNumber, int pageSize) {
        LocalDateTime now = LocalDateTime.now();
        boolean byName = sort.equals("name");
        Sort column = switch (sort) {
            case "violations" -> Sort.VIOLATIONS;
            case "startedat" -> Sort.STARTED_AT;
            case "progress" -> Sort.PROGRESS;
            default -> Sort.RISK;
        };

        // With a search text the matching users are asked for first, and name the rows too
        Mono<Search> search = term == null
                ? Mono.just(new Search(null, Map.of()))
                : participants.search(term).map(found -> new Search(found.keySet().toArray(new UUID[0]), found));

        return Mono.zip(search, monitoringRepository.countActiveQuestions(exam.getId()))
                .flatMap(tuple -> {
                    Search found = tuple.getT1();
                    int totalQuestions = tuple.getT2();
                    Filter filter = new Filter(exam.getId(), found.userIds(), levelRank);
                    Mono<List<LiveSession>> rows = found.userIds() != null && found.userIds().length == 0
                            ? Mono.just(List.of())
                            : monitoringRepository.findLiveSessions(filter, now, riskScoreSql(), levelRankSql(), column, ascending,
                                            byName ? MAX_NAME_SORT_ROWS + 1 : pageSize,
                                            byName ? 0 : (long) pageNumber * pageSize)
                                    .collectList();
                    return rows.flatMap(list -> {
                        if (list.size() > MAX_NAME_SORT_ROWS) {
                            return Mono.error(new ValidationException("Too many rows to sort by name; narrow the list with q or riskLevel"));
                        }
                        // The exam-wide figures ride on every row; an empty page needs them from their own statement
                        Mono<Summary> summary = list.isEmpty()
                                ? monitoringRepository.summary(exam.getId(), now, riskScoreSql(), levelRankSql())
                                : Mono.just(new Summary(list.get(0).liveCount(), list.get(0).liveViolations(),
                                        list.get(0).liveCountsByLevel()));
                        Mono<Map<UUID, UserRef>> names = found.userIds() != null
                                ? Mono.just(found.users())
                                // One lookup for the whole page
                                : participants.names(list.stream().map(LiveSession::userId).toList());
                        return Mono.zip(summary, names).map(loaded -> {
                            Map<UUID, UserRef> users = loaded.getT2();
                            long matching = list.isEmpty() ? 0 : list.get(0).matching();
                            List<LiveSession> pageRows = list;
                            if (byName) {
                                Comparator<String> text = ascending ? Comparator.naturalOrder() : Comparator.reverseOrder();
                                pageRows = list.stream()
                                        .sorted(Comparator.comparing((LiveSession row) -> sortName(users.get(row.userId())),
                                                Comparator.nullsLast(text)).thenComparing(LiveSession::sessionId))
                                        .skip((long) pageNumber * pageSize)
                                        .limit(pageSize)
                                        .toList();
                            }
                            return ExamMonitoringResponse.builder()
                                    .examId(exam.getId())
                                    .title(exam.getTitle())
                                    .inProgressCount(loaded.getT1().liveCount())
                                    .violationCount(loaded.getT1().liveViolations())
                                    .riskCounts(riskCounts(loaded.getT1().countsByLevel()))
                                    .totalQuestions(totalQuestions)
                                    .generatedAt(now)
                                    .sessions(PageResponse.of(
                                            pageRows.stream().map(row -> toSession(row, users.get(row.userId()), totalQuestions)).toList(),
                                            pageNumber, pageSize, matching))
                                    .build();
                        });
                    });
                });
    }

    private ExamMonitoringResponse.Session toSession(LiveSession row, UserRef user, int totalQuestions) {
        return ExamMonitoringResponse.Session.builder()
                .sessionId(row.sessionId())
                .userId(row.userId())
                .participantName(ParticipantDirectory.fullName(user))
                .email(user != null ? user.getEmail() : null)
                .attemptNumber(row.attemptNumber())
                .startedAt(row.startedAt())
                .deadline(row.deadline())
                .answeredCount(row.answeredCount())
                .totalQuestions(totalQuestions)
                .violationCount(row.violationCount())
                .countsByType(row.countsByType())
                .riskScore(row.riskScore())
                .riskLevel(riskPolicy.levelOfRank(row.levelRank()))
                .lastEventAt(row.lastEventAt())
                .lastEventType(row.lastEventType())
                .build();
    }

    // Last name first, ignoring case
    private String sortName(UserRef user) {
        if (user == null) {
            return null;
        }
        String name = ((user.getLastName() == null ? "" : user.getLastName().trim()) + " "
                + (user.getFirstName() == null ? "" : user.getFirstName().trim())).trim().toLowerCase(Locale.ROOT);
        return name.isEmpty() ? null : name;
    }

    private Integer levelRank(String riskLevel) {
        if (riskLevel == null || riskLevel.isBlank()) {
            return null;
        }
        try {
            return RiskLevel.valueOf(riskLevel.trim().toUpperCase(Locale.ROOT)).ordinal();
        } catch (IllegalArgumentException e) {
            throw new ValidationException("riskLevel must be LOW, MEDIUM, HIGH or CRITICAL");
        }
    }

    private Map<RiskLevel, Integer> riskCounts(int[] countsByLevel) {
        Map<RiskLevel, Integer> counts = new EnumMap<>(RiskLevel.class);
        for (RiskLevel level : RiskLevel.values()) {
            counts.put(level, countsByLevel[level.ordinal()]);
        }
        return counts;
    }

    private RiskLevel highest(int[] countsByLevel) {
        for (int rank = countsByLevel.length - 1; rank > 0; rank--) {
            if (countsByLevel[rank] > 0) {
                return riskPolicy.levelOfRank(rank);
            }
        }
        return RiskLevel.LOW;
    }

    private String riskScoreSql() {
        return riskPolicy.scoreSql("e.type");
    }

    private String levelRankSql() {
        return riskPolicy.levelRankSql("sc.risk_score");
    }
}
