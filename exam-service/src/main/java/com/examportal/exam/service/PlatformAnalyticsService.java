package com.examportal.exam.service;

import com.examportal.common.domain.dto.analytics.PlatformOverviewResponse;
import com.examportal.common.domain.dto.internal.UserStatsRequest;
import com.examportal.common.domain.dto.internal.UserStatsResponse;
import com.examportal.common.domain.entity.ExamStatus;
import com.examportal.common.exception.ValidationException;
import com.examportal.exam.client.UserDirectoryClient;
import com.examportal.exam.repository.AnalyticsRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Platform Analytics Service
 * Figures over all exams, for ADMIN only (the caller's role is checked by the controller). Read only.
 *
 * The user figures come from auth-service as aggregate counts. When it cannot be reached the rest
 * is still answered, with usersAvailable = false; such an answer is not cached, so the user
 * figures are back as soon as auth-service is.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformAnalyticsService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final int TOP_EXAMS = 5;

    private final AnalyticsRepository analyticsRepository;
    private final UserDirectoryClient userDirectory;
    private final ExamDayClock dayClock;
    private final ReadCache cache;

    /**
     * The overview for the last `days` days (1..90, 30 when omitted)
     */
    public Mono<PlatformOverviewResponse> getOverview(Integer days) {
        int window = days == null ? UserStatsRequest.DEFAULT_DAYS : days;
        if (window < 1 || window > UserStatsRequest.MAX_DAYS) {
            throw new ValidationException("days must be between 1 and " + UserStatsRequest.MAX_DAYS);
        }
        return cache.get("analytics:platform:" + window, CACHE_TTL, new TypeReference<PlatformOverviewResponse>() {
        }, () -> load(window), PlatformOverviewResponse::isUsersAvailable);
    }

    // ==================== Private Helper Methods ====================

    private Mono<PlatformOverviewResponse> load(int days) {
        LocalDateTime now = LocalDateTime.now();
        ExamDayClock.Window window = dayClock.lastDays(days);

        // A failing user directory only leaves the user figures out
        Mono<Optional<UserStatsResponse>> users = userDirectory.stats(days)
                .map(Optional::of)
                .onErrorResume(e -> {
                    log.warn("User figures could not be loaded: {}", e.getClass().getSimpleName());
                    return Mono.just(Optional.empty());
                });

        return Mono.zip(
                        analyticsRepository.countExamsByStatus().collectList(),
                        analyticsRepository.findPlatformFigures(now),
                        analyticsRepository.findPlatformDays(dayClock.zoneId(), window.firstDay(), window.lastDay(),
                                window.fromUtc()).collectList(),
                        analyticsRepository.findTopExams(window.fromUtc(), TOP_EXAMS).collectList(),
                        users)
                .map(loaded -> {
                    Map<ExamStatus, Integer> examsByStatus = new EnumMap<>(ExamStatus.class);
                    for (ExamStatus status : ExamStatus.values()) {
                        examsByStatus.put(status, 0);
                    }
                    loaded.getT1().forEach(count -> examsByStatus.put(ExamStatus.valueOf(count.status()), count.count()));

                    return PlatformOverviewResponse.builder()
                            .generatedAt(now)
                            .days(days)
                            .examsByStatus(examsByStatus)
                            .activeNowInProgressSessions(loaded.getT2().activeNow())
                            .pendingGradingTotal(loaded.getT2().pendingGrading())
                            .averagePercentageOverall(loaded.getT2().averagePercentage())
                            .passRateOverall(loaded.getT2().passRate())
                            .sessionsPerDay(loaded.getT3().stream()
                                    .map(day -> new PlatformOverviewResponse.DayCount(day.day().toString(), day.sessions()))
                                    .toList())
                            .uniqueParticipantsPerDay(loaded.getT3().stream()
                                    .map(day -> new PlatformOverviewResponse.DayCount(day.day().toString(), day.participants()))
                                    .toList())
                            .topExamsBySessions(loaded.getT4().stream()
                                    .map(exam -> PlatformOverviewResponse.TopExam.builder()
                                            .examId(exam.examId())
                                            .title(exam.title())
                                            .status(ExamStatus.valueOf(exam.status()))
                                            .sessionCount(exam.sessions())
                                            .participantCount(exam.participants())
                                            .build())
                                    .toList())
                            .usersAvailable(loaded.getT5().isPresent())
                            .users(loaded.getT5().orElse(null))
                            .build();
                });
    }
}
