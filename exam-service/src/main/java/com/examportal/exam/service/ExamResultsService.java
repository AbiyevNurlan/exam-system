package com.examportal.exam.service;

import com.examportal.common.domain.AzerbaijaniOrder;
import com.examportal.common.config.UtcDateTimeModule;
import com.examportal.common.domain.dto.BlockResultResponse;
import com.examportal.common.domain.dto.ExamResultsSummaryResponse;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.SessionReviewResponse;
import com.examportal.common.domain.dto.SessionSummaryResponse;
import com.examportal.common.domain.dto.internal.UserLookupResponse.UserRef;
import com.examportal.common.domain.entity.Exam;
import com.examportal.common.domain.entity.ExamSession;
import com.examportal.common.domain.entity.FraudEventType;
import com.examportal.common.domain.entity.GradingStatus;
import com.examportal.common.domain.entity.Question;
import com.examportal.common.domain.entity.QuestionOption;
import com.examportal.common.domain.entity.RiskLevel;
import com.examportal.common.domain.entity.SessionStatus;
import com.examportal.common.domain.entity.UserAnswer;
import com.examportal.common.exception.ValidationException;
import com.examportal.exam.exception.ExamException;
import com.examportal.exam.repository.BlockResultRepository;
import com.examportal.exam.repository.ExamResultsRepository;
import com.examportal.exam.repository.ExamResultsRepository.Filter;
import com.examportal.exam.repository.ExamResultsRepository.RankedBest;
import com.examportal.exam.repository.ExamResultsRepository.ResultRow;
import com.examportal.exam.repository.ExamResultsRepository.RiskSql;
import com.examportal.exam.repository.ExamResultsRepository.Sort;
import com.examportal.exam.repository.ExamResultsRepository.TypeCount;
import com.examportal.exam.repository.ExamSessionRepository;
import com.examportal.exam.repository.QuestionOptionRepository;
import com.examportal.exam.repository.QuestionRepository;
import com.examportal.exam.repository.UserAnswerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Exam Results Service
 * What ADMIN and the INSTRUCTOR who owns an exam see of its results: the list of sessions with
 * search, filters and sorting, the summary figures, and the full review of one session.
 * Names and emails come from auth-service through the ParticipantDirectory, one lookup per page.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamResultsService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_REVIEW_EVENTS = 300;
    private static final int TOP = 3;
    // Sorting by name happens here, not in SQL (names live in auth-service): only up to this many rows
    private static final int MAX_NAME_SORT_ROWS = 10_000;
    // Rows are named in blocks of EXPORT_BLOCK
    private static final int EXPORT_BLOCK = 500;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final List<String> EXPORT_HEADERS = List.of(
            "Sessiya ID", "Ad Soyad", "E-poçt", "Cəhd", "Status", "Başlama (UTC)", "Təhvil (UTC)", "Müddət (san)",
            "Bal", "Maks. bal", "Faiz", "Keçdi", "Qiymətləndirmə", "Təhvil növü", "Pozuntu sayı", "Risk səviyyəsi",
            // B10: added last, so the columns before it keep their place for whoever reads the file
            "İştirakçı kodu");

    private final ExamResultsRepository resultsRepository;
    private final ExamSessionRepository sessionRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final UserAnswerRepository answerRepository;
    private final ExamAccessPolicy accessPolicy;
    private final ParticipantDirectory participants;
    private final FraudRiskPolicy riskPolicy;
    private final ExamMediaService examMedia;
    private final GroupAuditService auditService;
    // B12a: results per block
    private final BlockResultRepository blockResults;

    // An export larger than this is refused (422 RESULTS_EXPORT_TOO_LARGE); EXAM_RESULTS_EXPORT_MAX_ROWS
    @Value("${exam.results.export-max-rows:50000}")
    private int maxExportRows = 50_000;

    /**
     * What a list request asks for; null = not given
     */
    public record ListQuery(String q, SessionStatus status, Boolean passed, GradingStatus gradingStatus,
                            Boolean hasViolations, Boolean bestOnly, String sort, String order,
                            RiskLevel riskLevel, BigDecimal minPercent, BigDecimal maxPercent) {

        public ListQuery(String q, SessionStatus status, Boolean passed, GradingStatus gradingStatus,
                         Boolean hasViolations, Boolean bestOnly, String sort, String order) {
            this(q, status, passed, gradingStatus, hasViolations, bestOnly, sort, order, null, null, null);
        }

        /**
         * A request that uses none of the newer parameters is answered as the list always was:
         * every session, also those in progress.
         */
        boolean legacy() {
            return q == null && passed == null && gradingStatus == null && hasViolations == null
                    && bestOnly == null && sort == null && order == null
                    && riskLevel == null && minPercent == null && maxPercent == null;
        }
    }

    /**
     * A list request checked and turned into what the repository takes (the same for the page and the export)
     */
    private record Prepared(String term, String sort, boolean ascending, boolean bestOnly, RiskSql riskSql) {

        Sort column() {
            return switch (sort) {
                case "score" -> Sort.SCORE;
                case "violations" -> Sort.VIOLATIONS;
                case "risk" -> Sort.RISK;
                default -> Sort.SUBMITTED_AT;
            };
        }

        Filter filter(Long examId, UUID[] userIds, ListQuery query) {
            return new Filter(examId, userIds, query.status(), query.passed(), query.gradingStatus(),
                    query.hasViolations(), bestOnly,
                    query.riskLevel() != null ? query.riskLevel().ordinal() : null,
                    query.minPercent(), query.maxPercent());
        }
    }

    private Prepared prepare(ListQuery query) {
        String term = participants.searchTerm(query.q());
        String sort = query.sort() == null || query.sort().isBlank() ? "submittedat" : query.sort().trim().toLowerCase(Locale.ROOT);
        if (!Set.of("score", "name", "violations", "submittedat", "risk").contains(sort)) {
            throw new ValidationException("sort must be score, name, violations, risk or submittedAt");
        }
        String order = query.order() == null || query.order().isBlank() ? null : query.order().trim().toLowerCase(Locale.ROOT);
        if (order != null && !order.equals("asc") && !order.equals("desc")) {
            throw new ValidationException("order must be asc or desc");
        }
        BigDecimal min = query.minPercent();
        BigDecimal max = query.maxPercent();
        if ((min != null && (min.signum() < 0 || min.compareTo(HUNDRED) > 0))
                || (max != null && (max.signum() < 0 || max.compareTo(HUNDRED) > 0))) {
            throw new ValidationException("minPercent and maxPercent must be between 0 and 100");
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new ValidationException("minPercent cannot be greater than maxPercent");
        }
        // Names read best from A, everything else from the highest or the newest
        boolean ascending = order != null ? order.equals("asc") : sort.equals("name");
        boolean bestOnly = query.bestOnly() != null ? query.bestOnly() : !query.legacy();
        return new Prepared(term, sort, ascending, bestOnly, new RiskSql(riskPolicy.scoreSql("e.type"),
                riskPolicy.levelRankSql(ExamResultsRepository.LISTED_RISK_SCORE)));
    }

    /**
     * With a search text the matching users are asked for first, and name the rows too
     */
    private Mono<Search> search(String term) {
        return term == null
                ? Mono.just(new Search(null, Map.of()))
                : participants.search(term).map(found -> new Search(found.keySet().toArray(new UUID[0]), found));
    }

    /**
     * One page of an exam's sessions. bestOnly (default true) keeps one row per participant: the
     * completed attempt with the highest percentage. sort: score, name, violations, risk or
     * submittedAt (default, newest first). riskLevel, minPercent and maxPercent narrow the list.
     */
    public Mono<PageResponse<SessionSummaryResponse>> getResults(Long examId, ListQuery query, int page, int size,
                                                                 UUID userId, String role) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Prepared prepared = prepare(query);
        String sort = prepared.sort();
        boolean ascending = prepared.ascending();
        RiskSql riskSql = prepared.riskSql();

        return accessPolicy.findManageableExam(examId, userId, role)
                .flatMap(exam -> search(prepared.term()))
                .flatMap(search -> {
                    if (search.userIds() != null && search.userIds().length == 0) {
                        return Mono.just(PageResponse.<SessionSummaryResponse>of(List.of(), pageNumber, pageSize, 0));
                    }
                    Filter filter = prepared.filter(examId, search.userIds(), query);
                    if (sort.equals("name")) {
                        return pageByName(filter, riskSql, search, ascending, pageNumber, pageSize);
                    }
                    Sort column = prepared.column();
                    return resultsRepository.count(filter, riskSql)
                            .flatMap(total -> resultsRepository.findPage(filter, riskSql, column, ascending, pageSize,
                                            (long) pageNumber * pageSize)
                                    .collectList()
                                    .flatMap(rows -> names(search, rows)
                                            .map(users -> PageResponse.of(
                                                    rows.stream().map(row -> toSummary(row, users.get(row.userId()))).toList(),
                                                    pageNumber, pageSize, total))));
                });
    }

    /**
     * The figures of one exam (see ExamResultsSummaryResponse for what is counted how)
     */
    public Mono<ExamResultsSummaryResponse> getSummary(Long examId, UUID userId, String role) {
        return accessPolicy.findManageableExam(examId, userId, role)
                .flatMap(exam -> Mono.zip(
                        resultsRepository.counts(examId),
                        resultsRepository.findRankedBest(examId, riskPolicy.scoreSql("e.type")).collectList()))
                .flatMap(tuple -> {
                    List<RankedBest> ranked = tuple.getT2();
                    // At most 12 participants: one lookup names them all
                    return participants.names(ranked.stream().map(RankedBest::userId).collect(Collectors.toSet()))
                            .map(users -> {
                                RankedBest any = ranked.isEmpty() ? null : ranked.get(0);
                                return ExamResultsSummaryResponse.builder()
                                        .participantCount(tuple.getT1().participantCount())
                                        .sessionCount(tuple.getT1().sessionCount())
                                        .completedCount(tuple.getT1().completedCount())
                                        .inProgressCount(tuple.getT1().inProgressCount())
                                        .pendingGradingCount(tuple.getT1().pendingGradingCount())
                                        .rankedParticipantCount(any != null ? any.rankedCount() : 0)
                                        .averagePercentage(any != null ? any.averagePercentage() : null)
                                        .passRate(any != null ? any.passRate() : null)
                                        .cleanParticipantCount(any != null ? any.cleanCount() : 0)
                                        .topScorers(top(ranked, RankedBest::topRank, false, users))
                                        .lowestScorers(top(ranked, RankedBest::lowRank, false, users))
                                        .mostViolations(top(ranked, RankedBest::mostRank, true, users))
                                        .fewestViolations(top(ranked, RankedBest::fewestRank, false, users))
                                        .build();
                            });
                });
    }

    /**
     * Everything about one session, with the answer key: for the managers of its exam only
     */
    public Mono<SessionReviewResponse> getReview(String sessionId, UUID userId, String role) {
        // A session of another owner's exam is the same 404 SESSION_NOT_FOUND as a missing one (B13)
        return accessPolicy.findManageableSession(sessionId, userId, role)
                .flatMap(session -> accessPolicy.findManageableExam(session.getExamId(), userId, role)
                        .flatMap(exam -> review(session, exam)));
    }

    // ==================== Private Helper Methods ====================

    private record Search(UUID[] userIds, Map<UUID, UserRef> users) {
    }

    private Mono<Map<UUID, UserRef>> names(Search search, List<ResultRow> rows) {
        return search.userIds() != null
                ? Mono.just(search.users())
                // One lookup for the whole page
                : participants.names(rows.stream().map(ResultRow::userId).toList());
    }

    /**
     * Sorted by name: the matching rows are read with one statement, named in batches and sorted
     * here; rows without a known name come last. Refused when the list is too long for that.
     */
    private Mono<PageResponse<SessionSummaryResponse>> pageByName(Filter filter, RiskSql riskSql, Search search,
                                                                  boolean ascending, int pageNumber, int pageSize) {
        return resultsRepository.findPage(filter, riskSql, Sort.SUBMITTED_AT, false, MAX_NAME_SORT_ROWS + 1, 0)
                .collectList()
                .flatMap(rows -> {
                    if (rows.size() > MAX_NAME_SORT_ROWS) {
                        return Mono.error(new ValidationException("Too many rows to sort by name; narrow the list with q or a filter"));
                    }
                    return names(search, rows).map(users -> {
                        List<SessionSummaryResponse> pageRows = sortedByName(rows, users, ascending).stream()
                                .skip((long) pageNumber * pageSize)
                                .limit(pageSize)
                                .map(row -> toSummary(row, users.get(row.userId())))
                                .toList();
                        return PageResponse.of(pageRows, pageNumber, pageSize, rows.size());
                    });
                });
    }

    private List<ResultRow> sortedByName(List<ResultRow> rows, Map<UUID, UserRef> users, boolean ascending) {
        // Azerbaijani alphabetical order ignoring case (B11b); rows without a known name last
        Comparator<ResultRow> byName = Comparator.comparing(
                (ResultRow row) -> sortName(users.get(row.userId())),
                AzerbaijaniOrder.names(ascending));
        return rows.stream().sorted(byName.thenComparing(ResultRow::sessionId)).toList();
    }

    // Last name first; the case is left to the comparator (lower() would turn I into i instead of ı)
    private String sortName(UserRef user) {
        if (user == null) {
            return null;
        }
        String name = ((user.getLastName() == null ? "" : user.getLastName().trim()) + " "
                + (user.getFirstName() == null ? "" : user.getFirstName().trim())).trim();
        return name.isEmpty() ? null : name;
    }

    private SessionSummaryResponse toSummary(ResultRow row, UserRef user) {
        return SessionSummaryResponse.builder()
                .sessionId(row.sessionId())
                .userId(row.userId())
                .participantName(ParticipantDirectory.fullName(user))
                .email(user != null ? user.getEmail() : null)
                .participantCode(user != null ? user.getParticipantCode() : null)
                .attemptNumber(row.attemptNumber())
                .status(row.status())
                .startedAt(row.startedAt())
                .submittedAt(row.submittedAt())
                .durationSeconds(durationSeconds(row.startedAt(), row.submittedAt()))
                .score(row.score())
                .maxScore(row.maxScore())
                .percentage(row.percentage())
                .passed(row.passed())
                .gradingStatus(row.gradingStatus())
                .violationCount(row.violationCount())
                .riskLevel(riskPolicy.level(row.riskScore()))
                .submitKind(row.submitKind() != null ? row.submitKind().name() : null)
                .build();
    }

    // ==================== CSV export ====================

    /**
     * The file name and the streamed body of an export
     */
    public record Export(String fileName, long rows, Flux<DataBuffer> body) {
    }

    /**
     * The same list as getResults (same filters, search and order) as a CSV file: UTF-8 with a BOM,
     * comma separated, Azerbaijani headers, every row of the list without paging. More than
     * maxExportRows (default 50 000) rows is refused (422) before anything is sent. The rows are read with one
     * statement and streamed; names are looked up per block of rows. One audit row records who
     * exported which exam and how many rows.
     */
    public Mono<Export> export(Long examId, ListQuery query, UUID userId, String role) {
        Prepared prepared = prepare(query);
        return accessPolicy.findManageableExam(examId, userId, role)
                .flatMap(exam -> search(prepared.term()).flatMap(search -> {
                    if (search.userIds() != null && search.userIds().length == 0) {
                        return audit(userId, exam, 0, prepared).thenReturn(csv(examId, 0, Flux.empty(), search));
                    }
                    Filter filter = prepared.filter(examId, search.userIds(), query);
                    return resultsRepository.count(filter, prepared.riskSql())
                            .flatMap(total -> {
                                if (total > maxExportRows) {
                                    return Mono.error(ExamException.exportTooLarge(maxExportRows));
                                }
                                if (prepared.sort().equals("name") && total > MAX_NAME_SORT_ROWS) {
                                    return Mono.error(new ValidationException("Too many rows to sort by name; narrow the list with q or a filter"));
                                }
                                Flux<ResultRow> rows = prepared.sort().equals("name")
                                        // Names live in auth-service: read, named and sorted here (at most 10 000)
                                        ? resultsRepository.findPage(filter, prepared.riskSql(), Sort.SUBMITTED_AT, false, MAX_NAME_SORT_ROWS, 0)
                                                .collectList()
                                                .flatMap(all -> names(search, all).map(users -> sortedByName(all, users, prepared.ascending())))
                                                .flatMapIterable(sorted -> sorted)
                                        : resultsRepository.findPage(filter, prepared.riskSql(), prepared.column(), prepared.ascending(),
                                                maxExportRows, 0);
                                return audit(userId, exam, total, prepared).thenReturn(csv(examId, total, rows, search));
                            });
                }));
    }

    private Mono<Void> audit(UUID userId, Exam exam, long rows, Prepared prepared) {
        return auditService.record(userId, GroupAuditService.RESULTS_EXPORTED, null, exam.getCreatedBy(),
                "examId=" + exam.getId() + " rows=" + rows + " sort=" + prepared.sort() + " bestOnly=" + prepared.bestOnly()
                        + (prepared.term() != null ? " search=yes" : ""));
    }

    private Export csv(Long examId, long total, Flux<ResultRow> rows, Search search) {
        Flux<DataBuffer> lines = rows
                .buffer(EXPORT_BLOCK)
                .concatMap(block -> names(search, block).map(users -> {
                    StringBuilder text = new StringBuilder();
                    for (ResultRow row : block) {
                        appendRow(text, toSummary(row, users.get(row.userId())));
                    }
                    return buffer(text.toString());
                }));
        StringBuilder head = new StringBuilder("﻿");
        appendLine(head, EXPORT_HEADERS);
        return new Export("exam-" + examId + "-results.csv", total, Flux.concat(Flux.just(buffer(head.toString())), lines));
    }

    private void appendRow(StringBuilder text, SessionSummaryResponse row) {
        appendLine(text, Arrays.asList(
                row.getSessionId(),
                row.getParticipantName(),
                row.getEmail(),
                String.valueOf(row.getAttemptNumber()),
                row.getStatus() != null ? row.getStatus().name() : null,
                utc(row.getStartedAt()),
                // Empty while the session is in progress
                utc(row.getSubmittedAt()),
                row.getDurationSeconds() != null ? String.valueOf(row.getDurationSeconds()) : null,
                plain(row.getScore()),
                plain(row.getMaxScore()),
                plain(row.getPercentage()),
                row.getPassed() == null ? null : (row.getPassed() ? "Bəli" : "Xeyr"),
                row.getGradingStatus() != null ? row.getGradingStatus().name() : null,
                row.getSubmitKind(),
                String.valueOf(row.getViolationCount()),
                row.getRiskLevel() != null ? row.getRiskLevel().name() : null,
                row.getParticipantCode() != null ? String.valueOf(row.getParticipantCode()) : null));
    }

    private static void appendLine(StringBuilder text, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(csvCell(cells.get(i)));
        }
        text.append("\r\n");
    }

    /**
     * One CSV cell: quoted when it holds a comma, quote or line break, and with a leading apostrophe
     * when it starts with =, +, -, @ (or a tab / carriage return), so a spreadsheet never reads it as
     * a formula (CSV injection)
     */
    static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        String cell = value;
        if (!cell.isEmpty() && "=+-@\t\r".indexOf(cell.charAt(0)) >= 0) {
            cell = "'" + cell;
        }
        if (cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r")) {
            cell = "\"" + cell.replace("\"", "\"\"") + "\"";
        }
        return cell;
    }

    private static String utc(LocalDateTime value) {
        return value == null ? null : UtcDateTimeModule.format(value);
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static DataBuffer buffer(String text) {
        return DefaultDataBufferFactory.sharedInstance.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    private Long durationSeconds(LocalDateTime start, LocalDateTime end) {
        return start == null || end == null ? null : Math.max(0, Duration.between(start, end).getSeconds());
    }

    /**
     * The first three of one ranking, in rank order
     */
    private List<ExamResultsSummaryResponse.Scorer> top(List<RankedBest> ranked, ToIntFunction<RankedBest> rank,
                                                        boolean withViolationsOnly, Map<UUID, UserRef> users) {
        return ranked.stream()
                .filter(row -> rank.applyAsInt(row) <= TOP)
                .filter(row -> !withViolationsOnly || row.violationCount() > 0)
                .sorted(Comparator.comparingInt(rank))
                .map(row -> ExamResultsSummaryResponse.Scorer.builder()
                        .userId(row.userId())
                        .participantName(ParticipantDirectory.fullName(users.get(row.userId())))
                        .participantCode(users.get(row.userId()) != null ? users.get(row.userId()).getParticipantCode() : null)
                        .sessionId(row.sessionId())
                        .percentage(row.percentage())
                        .score(row.score())
                        .maxScore(row.maxScore())
                        .violationCount(row.violationCount())
                        .riskLevel(riskPolicy.level(row.riskScore()))
                        .build())
                .toList();
    }

    private record ReviewData(List<Question> questions, Map<Long, Collection<QuestionOption>> options,
                              Map<Long, UserAnswer> answers, ExamMediaService.MediaIndex media,
                              List<ExamResultsRepository.FraudEvent> events, List<TypeCount> eventCounts,
                              int attemptNumber, List<BlockResultResponse> blocks) {
    }

    private Mono<SessionReviewResponse> review(ExamSession session, Exam exam) {
        Mono<List<Question>> questions = questionRepository.findActiveByExamIdOrderByOrderIndex(exam.getId()).collectList();

        return questions
                .flatMap(list -> Mono.zip(
                                list.isEmpty()
                                        ? Mono.just(Map.<Long, Collection<QuestionOption>>of())
                                        : optionRepository.findByQuestionIdIn(list.stream().map(Question::getId).toList())
                                                .collectMultimap(QuestionOption::getQuestionId),
                                answerRepository.findBySessionId(session.getId())
                                        // One answer per question (uq_user_answers_session_question)
                                        .collectMap(UserAnswer::getQuestionId),
                                examMedia.loadIndexWithPreviews(exam.getId()),
                                resultsRepository.findFraudEvents(session.getId(), MAX_REVIEW_EVENTS).collectList(),
                                resultsRepository.countFraudEventsByType(session.getId()).collectList(),
                                resultsRepository.attemptNumber(exam.getId(), session.getUserId(), session.getStartTime(),
                                        session.getId()),
                                blockResults.forSession(exam.getId(), session.getId()).collectList())
                        .map(t -> new ReviewData(list, t.getT1(), t.getT2(), t.getT3(), t.getT4(), t.getT5(), t.getT6(),
                                t.getT7())))
                // The participant and the graders are named with one lookup
                .flatMap(data -> participants.names(Stream.concat(
                                        Stream.of(session.getUserId()),
                                        data.answers().values().stream().map(UserAnswer::getGradedBy).filter(Objects::nonNull))
                                .collect(Collectors.toSet()))
                        .map(users -> toReview(session, exam, data, users)));
    }

    private SessionReviewResponse toReview(ExamSession session, Exam exam, ReviewData data, Map<UUID, UserRef> users) {
        UserRef participant = users.get(session.getUserId());

        List<SessionReviewResponse.QuestionReview> questions = data.questions().stream()
                .map(question -> {
                    UserAnswer answer = data.answers().get(question.getId());
                    Set<Long> selected = answer != null && answer.getSelectedOptionIds() != null
                            ? new HashSet<>(Arrays.asList(answer.getSelectedOptionIds()))
                            : Set.of();
                    List<SessionReviewResponse.OptionReview> options = data.options()
                            .getOrDefault(question.getId(), List.of()).stream()
                            .sorted(Comparator.comparing(QuestionOption::getOrderIndex, Comparator.nullsLast(Comparator.naturalOrder())))
                            .map(option -> SessionReviewResponse.OptionReview.builder()
                                    .id(option.getId())
                                    .optionText(option.getOptionText())
                                    .matchText(option.getMatchText())
                                    .orderIndex(option.getOrderIndex())
                                    .correct(Boolean.TRUE.equals(option.getIsCorrect()))
                                    .selected(selected.contains(option.getId()))
                                    .media(data.media().forOption(option.getId()))
                                    .build())
                            .toList();

                    SessionReviewResponse.QuestionReview.QuestionReviewBuilder review = SessionReviewResponse.QuestionReview.builder()
                            .questionId(question.getId())
                            .blockId(question.getBlockId())
                            .orderIndex(question.getOrderIndex())
                            .questionText(question.getQuestionText())
                            .questionType(question.getQuestionType())
                            .maxPoints(question.getPoints())
                            .explanation(question.getExplanation())
                            .media(data.media().forQuestion(question.getId()))
                            .scale(com.examportal.common.domain.dto.ScaleSettings.of(question.getScaleMin(), question.getScaleMax(),
                                    question.getScaleMinLabel(), question.getScaleMaxLabel()))
                            .options(options)
                            .answered(answer != null);
                    if (answer == null) {
                        return review.build();
                    }
                    return review
                            .answerId(answer.getId())
                            .selectedOptionIds(answer.getSelectedOptionIds() != null
                                    ? Arrays.asList(answer.getSelectedOptionIds())
                                    : List.of())
                            .textAnswer(answer.getTextAnswer())
                            .answeredAt(answer.getAnsweredAt())
                            .pointsEarned(answer.getPointsEarned())
                            .correct(answer.getIsCorrect())
                            .needsManualReview(Boolean.TRUE.equals(answer.getNeedsManualReview()))
                            .feedback(answer.getFeedback())
                            // An ungraded answer has no grader; the names may be an immutable Map.of() (failed lookup)
                            .gradedBy(answer.getGradedBy() != null
                                    ? ParticipantDirectory.fullName(users.get(answer.getGradedBy())) : null)
                            .gradedById(answer.getGradedBy())
                            .gradedAt(answer.getGradedAt())
                            .gradeVersion(answer.getGradeVersion())
                            .build();
                })
                .toList();

        Map<FraudEventType, Integer> countByType = new EnumMap<>(FraudEventType.class);
        data.eventCounts().forEach(count -> countByType.put(count.type(), count.count()));
        int riskScore = riskPolicy.score(countByType);

        return SessionReviewResponse.builder()
                .sessionId(session.getSessionId())
                .examId(exam.getId())
                .examTitle(exam.getTitle())
                .participant(SessionReviewResponse.Participant.builder()
                        .id(session.getUserId())
                        .name(ParticipantDirectory.fullName(participant))
                        .email(participant != null ? participant.getEmail() : null)
                        .participantCode(participant != null ? participant.getParticipantCode() : null)
                        .build())
                .attemptNumber(data.attemptNumber())
                .status(session.getStatus())
                .startedAt(session.getStartTime())
                .endTime(session.getEndTime())
                .submittedAt(session.getSubmittedAt())
                .durationSeconds(durationSeconds(session.getStartTime(), session.getSubmittedAt()))
                .score(session.getScore())
                .maxScore(session.getTotalPoints())
                .percentage(session.getPercentage())
                .passed(session.getPassed())
                .passingScore(exam.getPassingScore())
                .gradingStatus(session.getGradingStatus())
                .questions(questions)
                .blocks(data.blocks())
                .violations(SessionReviewResponse.Violations.builder()
                        .total(countByType.values().stream().mapToInt(Integer::intValue).sum())
                        .riskScore(riskScore)
                        .riskLevel(riskPolicy.level(riskScore))
                        .countByType(countByType)
                        .events(data.events().stream()
                                .map(event -> SessionReviewResponse.ViolationEvent.builder()
                                        .type(event.type())
                                        .occurredAt(event.occurredAt())
                                        .details(event.details())
                                        .build())
                                .toList())
                        .build())
                .build();
    }
}
