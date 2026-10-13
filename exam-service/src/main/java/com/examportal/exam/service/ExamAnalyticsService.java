package com.examportal.exam.service;

import com.examportal.common.domain.dto.analytics.ExamAnalyticsOverviewResponse;
import com.examportal.common.domain.dto.analytics.QuestionAnalyticsResponse;
import com.examportal.exam.repository.AnalyticsRepository;
import com.examportal.exam.repository.AnalyticsRepository.OptionStats;
import com.examportal.exam.repository.AnalyticsRepository.QuestionStats;
import com.examportal.exam.repository.AnalyticsRepository.TextAnswerStats;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Exam Analytics Service
 * Figures of one exam for ADMIN and the INSTRUCTOR who owns it: how every question was answered
 * and an overview of scores, attempts and days. Read only.
 *
 * Every score figure is over each participant's best completed attempt; sessions that still wait
 * for a grader are counted separately and never enter an average. The answers are cached for a
 * minute (ReadCache) and say when they were computed (generatedAt); the access check always runs
 * first and is never cached.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamAnalyticsService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final int SESSIONS_PER_DAY_WINDOW = 60;
    // Below these numbers of participants a difficulty / a discrimination index would be noise
    private static final int MIN_PARTICIPANTS_FOR_DIFFICULTY = 5;
    private static final int MIN_PARTICIPANTS_FOR_DISCRIMINATION = 10;
    private static final BigDecimal EASY_FROM = new BigDecimal("0.8");
    private static final BigDecimal MEDIUM_FROM = new BigDecimal("0.5");
    private static final String INSUFFICIENT_DATA = "INSUFFICIENT_DATA";
    private static final int RATE_SCALE = 4;

    private final AnalyticsRepository analyticsRepository;
    private final ExamAccessPolicy accessPolicy;
    private final ExamDayClock dayClock;
    private final ReadCache cache;

    /**
     * Every active question of the exam with how it was answered
     */
    public Mono<QuestionAnalyticsResponse> getQuestionAnalytics(Long examId, UUID userId, String role) {
        return accessPolicy.findManageableExam(examId, userId, role)
                .flatMap(exam -> cache.get("analytics:questions:" + examId + ":" + scope(userId, role), CACHE_TTL,
                        new TypeReference<QuestionAnalyticsResponse>() {
                        },
                        () -> Mono.zip(
                                        analyticsRepository.findQuestionStats(examId).collectList(),
                                        analyticsRepository.findOptionStats(examId).collectList(),
                                        analyticsRepository.findTopTextAnswers(examId).collectList())
                                .map(loaded -> toQuestionAnalytics(examId, loaded.getT1(), loaded.getT2(), loaded.getT3()))));
    }

    /**
     * Score distribution, counts, attempts per participant and sessions per day of the exam
     */
    public Mono<ExamAnalyticsOverviewResponse> getOverview(Long examId, UUID userId, String role) {
        return accessPolicy.findManageableExam(examId, userId, role)
                .flatMap(exam -> cache.get("analytics:overview:" + examId + ":" + scope(userId, role), CACHE_TTL,
                        new TypeReference<ExamAnalyticsOverviewResponse>() {
                        },
                        () -> {
                            ExamDayClock.Window window = dayClock.lastDays(SESSIONS_PER_DAY_WINDOW);
                            return Mono.zip(
                                            analyticsRepository.findExamFigures(examId),
                                            analyticsRepository.findSessionsPerDay(examId, dayClock.zoneId(), window.firstDay(),
                                                    window.lastDay(), window.fromUtc()).collectList())
                                    .map(loaded -> toOverview(examId, loaded.getT1(), loaded.getT2()));
                        }));
    }

    // ==================== Private Helper Methods ====================

    // Whose view the cached answer is: ADMIN shares one, an INSTRUCTOR has the own
    private String scope(UUID userId, String role) {
        return "ADMIN".equals(role) ? "admin" : userId.toString();
    }

    private QuestionAnalyticsResponse toQuestionAnalytics(Long examId, List<QuestionStats> questions,
                                                          List<OptionStats> options, List<TextAnswerStats> textAnswers) {
        Map<Long, List<OptionStats>> optionsByQuestion = options.stream().collect(Collectors.groupingBy(OptionStats::questionId));
        Map<Long, List<TextAnswerStats>> answersByQuestion = textAnswers.stream()
                .collect(Collectors.groupingBy(TextAnswerStats::questionId));
        int participants = questions.isEmpty() ? 0 : questions.get(0).participants();

        return QuestionAnalyticsResponse.builder()
                .examId(examId)
                .generatedAt(LocalDateTime.now())
                .participantCount(participants)
                .pendingGradingSessionCount(questions.isEmpty() ? 0 : questions.get(0).pendingSessions())
                .questions(questions.stream()
                        .map(question -> toQuestion(question, optionsByQuestion.getOrDefault(question.questionId(), List.of()),
                                answersByQuestion.getOrDefault(question.questionId(), List.of())))
                        .toList())
                .build();
    }

    private QuestionAnalyticsResponse.Question toQuestion(QuestionStats stats, List<OptionStats> options,
                                                          List<TextAnswerStats> textAnswers) {
        int participants = stats.participants();
        BigDecimal correctRate = rate(stats.correct(), stats.answered());
        BigDecimal possible = stats.maxPoints().multiply(BigDecimal.valueOf(participants));

        QuestionAnalyticsResponse.Question.QuestionBuilder question = QuestionAnalyticsResponse.Question.builder()
                .questionId(stats.questionId())
                .orderIndex(stats.orderIndex())
                .text(stats.text())
                .type(stats.type())
                .maxPoints(stats.maxPoints())
                .answeredCount(stats.answered())
                .unansweredCount(Math.max(0, participants - stats.answered()))
                .correctCount(stats.correct())
                .partiallyCorrectCount(stats.partial())
                .correctRate(correctRate)
                .averageScoreRate(possible.signum() > 0
                        ? stats.earned().divide(possible, RATE_SCALE, RoundingMode.HALF_UP)
                        : null)
                .difficulty(difficulty(participants, correctRate))
                .discriminationIndex(discrimination(participants, stats.upperCorrect(), stats.lowerCorrect()));

        switch (stats.type()) {
            case MULTIPLE_CHOICE, TRUE_FALSE -> question.options(options.stream()
                    .map(option -> QuestionAnalyticsResponse.Option.builder()
                            .optionId(option.optionId())
                            .text(option.text())
                            .correct(option.correct())
                            .selectedCount(option.selected())
                            .selectedRate(stats.answered() > 0 ? rate(option.selected(), stats.answered()) : BigDecimal.ZERO)
                            .build())
                    .toList());
            case OPEN_ENDED -> question
                    .gradedCount(stats.graded())
                    .pendingGradingCount(stats.pendingAnswers())
                    .averageGradePercent(stats.averageGradePercent());
            case FILL_BLANK -> question.topAnswers(textAnswers.stream()
                    .map(answer -> QuestionAnalyticsResponse.TextAnswer.builder()
                            .text(answer.text())
                            .count(answer.count())
                            .correct(answer.correct())
                            .build())
                    .toList());
        }
        return question.build();
    }

    private String difficulty(int participants, BigDecimal correctRate) {
        if (participants < MIN_PARTICIPANTS_FOR_DIFFICULTY || correctRate == null) {
            return INSUFFICIENT_DATA;
        }
        if (correctRate.compareTo(EASY_FROM) >= 0) {
            return "EASY";
        }
        return correctRate.compareTo(MEDIUM_FROM) >= 0 ? "MEDIUM" : "HARD";
    }

    /**
     * Share of correct answers in the top 27 % of the participants minus that in the bottom 27 %
     * (an unanswered question counts as not correct). The group size is the same the statement used.
     */
    private BigDecimal discrimination(int participants, int upperCorrect, int lowerCorrect) {
        if (participants < MIN_PARTICIPANTS_FOR_DISCRIMINATION) {
            return null;
        }
        long groupSize = Math.max(1, (27L * participants + 50) / 100);
        return BigDecimal.valueOf(upperCorrect - lowerCorrect)
                .divide(BigDecimal.valueOf(groupSize), RATE_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(int part, int whole) {
        return whole > 0
                ? BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), RATE_SCALE, RoundingMode.HALF_UP)
                : null;
    }

    private ExamAnalyticsOverviewResponse toOverview(Long examId, AnalyticsRepository.ExamFigures figures,
                                                     List<AnalyticsRepository.DayCount> days) {
        List<ExamAnalyticsOverviewResponse.ScoreBucket> distribution = new ArrayList<>();
        for (int bucket = 0; bucket < figures.buckets().length; bucket++) {
            distribution.add(ExamAnalyticsOverviewResponse.ScoreBucket.builder()
                    .from(bucket * 10)
                    .to(bucket * 10 + 10)
                    .label(bucket * 10 + "-" + (bucket * 10 + 10) + "%")
                    .count(figures.buckets()[bucket])
                    .build());
        }
        return ExamAnalyticsOverviewResponse.builder()
                .examId(examId)
                .generatedAt(LocalDateTime.now())
                .participantCount(figures.ranked())
                .totalParticipantCount(figures.totalParticipants())
                .sessionCount(figures.sessions())
                .completedCount(figures.completed())
                .inProgressCount(figures.inProgress())
                .pendingGradingCount(figures.pending())
                .expiredOrAutoSubmittedCount(figures.expired())
                .averageDurationSeconds(figures.averageDurationSeconds())
                .averagePercentage(figures.averagePercentage())
                .medianPercentage(figures.medianPercentage())
                .passRate(figures.passRate())
                .scoreDistribution(distribution)
                .attemptsPerParticipant(ExamAnalyticsOverviewResponse.Attempts.builder()
                        .one(figures.oneAttempt())
                        .two(figures.twoAttempts())
                        .threeOrMore(figures.threeOrMoreAttempts())
                        .build())
                .sessionsPerDay(days.stream()
                        .map(day -> ExamAnalyticsOverviewResponse.DayCount.builder()
                                .date(day.day().toString())
                                .count(day.count())
                                .build())
                        .toList())
                .build();
    }
}
