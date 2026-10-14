package com.examportal.exam.service;

import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.domain.dto.grading.PendingCountResponse;
import com.examportal.common.domain.dto.grading.PendingExamResponse;
import com.examportal.exam.exception.ExamException;
import com.examportal.exam.repository.GradingOverviewRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Grading Overview Service
 * Which exams wait for a grader and how much, for the grading screen and the menu badge:
 * ADMIN every exam, INSTRUCTOR the own ones. Both answers are cached for at most 10 seconds
 * (ReadCache); who may see what is decided before the cache is asked, and the key carries whose
 * view it is, so an instructor is never served another's figures.
 */
@Service
@RequiredArgsConstructor
public class GradingOverviewService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(10);
    private static final int MAX_PAGE_SIZE = 50;
    private static final String ADMIN = "ADMIN";
    private static final String INSTRUCTOR = "INSTRUCTOR";

    private final GradingOverviewRepository repository;
    private final ReadCache cache;

    public Mono<PageResponse<PendingExamResponse>> pendingExams(UUID userId, String role, int page, int size) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return owner(userId, role).flatMap(owner -> cache.get(
                "grading:pending-exams:" + key(owner) + ":" + pageNumber + ":" + pageSize, CACHE_TTL,
                new TypeReference<PageResponse<PendingExamResponse>>() {
                },
                () -> repository.findPendingExams(owner.orElse(null), pageSize, (long) pageNumber * pageSize)
                        .map(found -> PageResponse.of(found.rows(), pageNumber, pageSize, found.total()))));
    }

    public Mono<PendingCountResponse> pendingCount(UUID userId, String role) {
        return owner(userId, role).flatMap(owner -> cache.get(
                "grading:pending-count:" + key(owner), CACHE_TTL,
                new TypeReference<PendingCountResponse>() {
                },
                () -> repository.countPending(owner.orElse(null))));
    }

    /**
     * Whose exams the caller may see: empty for ADMIN (all), the caller for INSTRUCTOR; anybody
     * else is refused before anything is read or cached
     */
    private Mono<Optional<UUID>> owner(UUID userId, String role) {
        if (ADMIN.equals(role)) {
            return Mono.just(Optional.empty());
        }
        if (INSTRUCTOR.equals(role)) {
            return Mono.just(Optional.of(userId));
        }
        return Mono.error(new ExamException("Grading is for administrators and instructors",
                HttpStatus.FORBIDDEN, "ACCESS_DENIED"));
    }

    private String key(Optional<UUID> owner) {
        return owner.map(UUID::toString).orElse("all");
    }
}
