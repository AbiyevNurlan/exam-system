package com.examportal.exam.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Media Cleanup Job
 * Two clean-ups, both logging counts only:
 * - uploads that were started but never confirmed (PENDING longer than
 *   exam.media.pending-max-age-minutes): the object is deleted and the asset marked DELETED;
 * - confirmed files that no exam uses (ACTIVE, attached nowhere, older than
 *   exam.media.orphan-retention-days; see MediaService.cleanupOrphans). Counted in the metric
 *   media_orphan_deleted_total.
 */
@Slf4j
@Component
public class MediaCleanupJob {

    private final MediaService mediaService;
    private final Counter orphansDeleted;

    public MediaCleanupJob(MediaService mediaService, MeterRegistry meterRegistry) {
        this.mediaService = mediaService;
        // Exported to Prometheus as media_orphan_deleted_total
        this.orphansDeleted = Counter.builder("media.orphan.deleted")
                .description("Confirmed media files removed because no exam used them")
                .register(meterRegistry);
    }

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

    @Scheduled(initialDelayString = "${exam.media.orphan-cleanup-initial-delay-ms:90000}",
            fixedDelayString = "${exam.media.orphan-cleanup-interval-ms:1800000}")
    public void cleanupOrphans() {
        mediaService.cleanupOrphans()
                .subscribe(
                        removed -> {
                            orphansDeleted.increment(removed);
                            log.info("Media orphan cleanup: {} unused files removed", removed);
                        },
                        error -> log.error("Media orphan cleanup failed: {}", error.getClass().getSimpleName()));
    }
}