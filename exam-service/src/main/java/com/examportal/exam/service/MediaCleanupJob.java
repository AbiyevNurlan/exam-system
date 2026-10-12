package com.examportal.exam.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Media Cleanup Job
 * Removes uploads that were started but never confirmed (PENDING longer than
 * exam.media.pending-max-age-minutes): the object is deleted and the asset marked DELETED.
 * Logs only counts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaCleanupJob {

    private final MediaService mediaService;

    @Scheduled(initialDelayString = "${exam.media.cleanup-initial-delay-ms:60000}",
            fixedDelayString = "${exam.media.cleanup-interval-ms:300000}")
    public void cleanupStalePending() {
        mediaService.cleanupStalePending()
                .subscribe(
                        removed -> {
                            if (removed > 0) {
                                log.info("Media cleanup: {} unconfirmed uploads removed", removed);
                            }
                        },
                        error -> log.error("Media cleanup failed: {}", error.getClass().getSimpleName()));
    }
}