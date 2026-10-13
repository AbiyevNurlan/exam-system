package com.examportal.exam.service;

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
import com.examportal.common.domain.entity.SessionStatus;
import com.examportal.common.domain.entity.UserAnswer;
import com.examportal.common.exception.ValidationException;
import com.examportal.exam.exception.ExamException;
import com.examportal.exam.repository.ExamResultsRepository;
import com.examportal.exam.repository.ExamResultsRepository.Filter;
import com.examportal.exam.repository.ExamResultsRepository.RankedBest;
import com.examportal.exam.repository.ExamResultsRepository.ResultRow;
import com.examportal.exam.repository.ExamResultsRepository.Sort;
import com.examportal.exam.repository.ExamResultsRepository.TypeCount;
import com.examportal.exam.repository.ExamSessionRepository;
import com.examportal.exam.repository.QuestionOptionRepository;
import com.examportal.exam.repository.QuestionRepository;
import com.examportal.exam.repository.UserAnswerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

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

    private final ExamResultsRepository resultsRepository;
    private final ExamSessionRepository sessionRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final UserAnswerRepository answerRepository;
    private final ExamAccessPolicy accessPolicy;
    private final ParticipantDirectory participants;
    private final FraudRiskPolicy riskPolicy;
    private final ExamMediaService examMedia;

    /**
     * What a list request asks for; null = not given
     */
    public record ListQuery(String q, SessionStatus status, Boolean passed, GradingStatus gradingStatus,
                            Boolean hasViolations, Boolean bestOnly, String sort, String order) {

        /**
         * A request that uses none of the newer parameters is answered as the list always was:
         * every session, also those in progress.
         */
        boolean legacy() {
            return q == null && passed == null && gradingStatus == null && hasViolations == null
                    && bestOnly == null && sort == null && order == null;
        }
    }

    /**
     * One page of an exam's sessions. bestOnly (default true) keeps one row per participant: the
     * completed attempt with the highest percentage. sort: score, name, violations or submittedAt
     * (default, newest first).
     */
    public Mono<PageResponse<SessionSummaryResponse>> getResults(Long examId, ListQuery query, int page, int size,
                                                                 UUID userId, String role) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String term = participants.searchTerm(query.q());
        String sort = query.sort() == null || query.sort().isBlank() ? "submittedat" : query.sort().trim().toLowerCase(Locale.ROOT);
        if (!Set.of("score", "name", "violations", "submittedat").contains(sort)) {
            throw new ValidationException("sort must be score, name, violations or submittedAt");
        }
        String order = query.order() == null || query.order().isBlank() ? null : query.order().trim().toLowerCase(Locale.ROOT);
        if (order != null && !order.equals("asc") && !order.equals("desc")) {
            throw new ValidationException("order must be asc or desc");
        }
        // Names read best from A, everything else from the highest or the newest
        boolean ascending = order != null ? order.equals("asc") : sort.equals("name");
        boolean bestOnly = query.bestOnly() != null ? query.bestOnly() : !query.legacy();
        String riskSql = riskPolicy.scoreSql("e.type");

        return accessPolicy.findManageableExam(examId, userId, role)
                // With a search text the matching users are asked for first, and name the rows too
                .flatMap(exam -> term == null
                        ? Mono.just(new Search(null, Map.of()))
                        : participants.search(term).map(found -> new Search(found.keySet().toArray(new UUID[0]), found)))
                .flatMap(search -> {
                    if (search.userIds() != null && search.userIds().length == 0) {
                        return Mono.just(PageResponse.<SessionSummaryResponse>of(List.of(), pageNumber, pageSize, 0));
                    }
                    Filter filter = new Filter(examId, search.userIds(), query.status(), query.passed(),
                            query.gradingStatus(), query.hasViolations(), bestOnly);
                    if (sort.equals("name")) {
                        return pageByName(filter, riskSql, search, ascending, pageNumber, pageSize);
                    }
                    Sort column = switch (sort) {
                        case "score" -> Sort.SCORE;
                        case "violations" -> Sort.VIOLATIONS;
                        default -> Sort.SUBMITTED_AT;
                    };
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
        return sessionRepository.findBySessionId(sessionId)
                .switchIfEmpty(Mono.error(ExamException.sessionNotFound(sessionId)))
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
    private Mono<PageResponse<SessionSummaryResponse>> pageByName(Filter filter, String riskSql, Search search,
                                                                  boolean ascending, int pageNumber, int pageSize) {
        return resultsRepository.findPage(filter, riskSql, Sort.SUBMITTED_AT, false, MAX_NAME_SORT_ROWS + 1, 0)
                .collectList()
                .flatMap(rows -> {
                    if (rows.size() > MAX_NAME_SORT_ROWS) {
                        return Mono.error(new ValidationException("Too many rows to sort by name; narrow the list with q or a filter"));
                    }
                    return names(search, rows).map(users -> {
                        Comparator<String> text = ascending ? Comparator.naturalOrder() : Comparator.reverseOrder();
                        Comparator<ResultRow> byName = Comparator.comparing(
                                (ResultRow row) -> sortName(users.get(row.userId())),
                                Comparator.nullsLast(text));
                        List<SessionSummaryResponse> pageRows = rows.stream()
                                .sorted(byName.thenComparing(ResultRow::sessionId))
                                .skip((long) pageNumber * pageSize)
                                .limit(pageSize)
                                .map(row -> toSummary(row, users.get(row.userId())))
                                .toList();
                        return PageResponse.of(pageRows, pageNumber, pageSize, rows.size());
                    });
                });
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

    private SessionSummaryResponse toSummary(ResultRow row, UserRef user) {
        return SessionSummaryResponse.builder()
                .sessionId(row.sessionId())
                .userId(row.userId())
                .participantName(ParticipantDirectory.fullName(user))
                .email(user != null ? user.getEmail() : null)
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
                .build();
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
                              int attemptNumber) {
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
                                        session.getId()))
                        .map(t -> new ReviewData(list, t.getT1(), t.getT2(), t.getT3(), t.getT4(), t.getT5(), t.getT6())))
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
                                    .orderIndex(option.getOrderIndex())
                                    .correct(Boolean.TRUE.equals(option.getIsCorrect()))
                                    .selected(selected.contains(option.getId()))
                                    .media(data.media().forOption(option.getId()))
                                    .build())
                            .toList();

                    SessionReviewResponse.QuestionReview.QuestionReviewBuilder review = SessionReviewResponse.QuestionReview.builder()
                            .questionId(question.getId())
                            .orderIndex(question.getOrderIndex())
                            .questionText(question.getQuestionText())
                            .questionType(question.getQuestionType())
                            .maxPoints(question.getPoints())
                            .explanation(question.getExplanation())
                            .media(data.media().forQuestion(question.getId()))
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
                            .gradedBy(ParticipantDirectory.fullName(users.get(answer.getGradedBy())))
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
