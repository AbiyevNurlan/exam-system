package com.examportal.exam.service;

import com.examportal.exam.repository.ExamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Exam Scheduler Service
 * Handles scheduled tasks for exam lifecycle management.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamSchedulerService {

    private final ExamRepository examRepository;
    private final AnswerSubmissionService answerService;
    private final ExamCacheService cacheService;

    /**
     * Activate exams that have reached their start time
     * Runs every minute
     */
    @Scheduled(fixedRate = 60000)
    public void activateScheduledExams() {
        log.debug("Checking for exams to activate...");
        
        examRepository.findExamsToActivate(LocalDateTime.now())
                // The transition is decided again by the UPDATE itself: an exam that was rescheduled or
                // archived since the list was read is skipped
                .flatMap(exam -> examRepository.activateIfDue(exam.getId(), LocalDateTime.now())
                        .filter(changed -> changed == 1)
                        .flatMap(changed -> {
                            log.info("Activating exam: {} (ID: {})", exam.getTitle(), exam.getId());
                            return cacheService.invalidateExamCache(exam.getId()).thenReturn(exam);
                        }))
                .doOnError(err -> log.error("Error activating exam: {}", err.getMessage()))
                .subscribe(
                        exam -> log.info("Exam activated: {}", exam.getId()),
                        error -> log.error("Failed to activate exams", error)
                );
    }

    /**
     * Complete exams that have reached their end time
     * Runs every minute
     */
    @Scheduled(fixedRate = 60000)
    public void completeEndedExams() {
        log.debug("Checking for exams to complete...");
        
        examRepository.findExamsToComplete(LocalDateTime.now())
                // Same as activation: an exam that was just given a later end time stays ACTIVE
                .flatMap(exam -> examRepository.completeIfEnded(exam.getId(), LocalDateTime.now())
                        .filter(changed -> changed == 1)
                        .flatMap(changed -> {
                            log.info("Completing exam: {} (ID: {})", exam.getTitle(), exam.getId());
                            return cacheService.invalidateExamCache(exam.getId()).thenReturn(exam);
                        }))
                .doOnError(err -> log.error("Error completing exam: {}", err.getMessage()))
                .subscribe(
                        exam -> log.info("Exam completed: {}", exam.getId()),
                        error -> log.error("Failed to complete exams", error)
                );
    }

    /**
     * Auto-submit expired exam sessions
     * Runs every minute
     */
    @Scheduled(fixedRate = 60000)
    public void autoSubmitExpiredSessions() {
        log.debug("Checking for expired sessions to auto-submit...");
        
        answerService.autoSubmitExpiredSessions()
                .doOnError(err -> log.error("Error auto-submitting sessions: {}", err.getMessage()))
                .subscribe(
                        v -> log.debug("Auto-submit check completed"),
                        error -> log.error("Failed to auto-submit expired sessions", error)
                );
    }

    /**
     * Clean up old cache entries
     * Runs every hour
     */
    @Scheduled(fixedRate = 3600000)
    public void cleanupCache() {
        log.debug("Running cache cleanup...");
        // Redis handles TTL automatically, but we can add custom cleanup logic here if needed
    }

    /**
     * Manually trigger exam activation check
     */
    public Mono<Void> triggerActivationCheck() {
        log.info("Manual activation check triggered");
        activateScheduledExams();
        return Mono.empty();
    }

    /**
     * Manually trigger session auto-submit
     */
    public Mono<Void> triggerAutoSubmit() {
        log.info("Manual auto-submit triggered");
        return answerService.autoSubmitExpiredSessions();
    }
}
