package com.examportal.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * Account Cleanup Job
 * Daily housekeeping (auth.cleanup.*):
 *
 * - email_otp rows older than the retention are deleted. The send limits look back one hour and a
 *   code lives for minutes, so nothing that is still needed goes.
 * - Accounts that were never verified and have not been touched for the maximum age are switched
 *   off (active = false). Users are never deleted; registering the email again revives the account.
 *
 * Only counts are logged. Both statements use the database clock and run in one transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "auth.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class AccountCleanupJob {

    private final DatabaseClient databaseClient;
    private final TransactionalOperator transactionalOperator;

    @Value("${auth.cleanup.otp-retention-days:7}")
    private int otpRetentionDays;

    @Value("${auth.cleanup.unverified-max-age-days:14}")
    private int unverifiedMaxAgeDays;

    @Value("${auth.cleanup.run-on-startup:true}")
    private boolean runOnStartup;

    @Scheduled(cron = "${auth.cleanup.cron:0 30 3 * * *}")
    public void runScheduled() {
        run().subscribe();
    }

    /**
     * A service that is restarted every day would otherwise never reach its cron time
     */
    @EventListener(ApplicationReadyEvent.class)
    public void runAfterStartup() {
        if (runOnStartup) {
            run().subscribe();
        }
    }

    /**
     * One cleanup pass. Never fails: an error is logged and the next run tries again.
     */
    public Mono<Void> run() {
        Mono<Long> deleteOtps = databaseClient.sql("""
                        DELETE FROM email_otp
                        WHERE created_at < LOCALTIMESTAMP - make_interval(days => :days)
                        """)
                .bind("days", otpRetentionDays)
                .fetch()
                .rowsUpdated();

        Mono<Long> deactivateUsers = databaseClient.sql("""
                        UPDATE users
                        SET active = false, updated_at = CURRENT_TIMESTAMP
                        WHERE email_verified = false AND active = true
                          AND created_at < LOCALTIMESTAMP - make_interval(days => :days)
                          AND COALESCE(updated_at, created_at) < LOCALTIMESTAMP - make_interval(days => :days)
                        """)
                .bind("days", unverifiedMaxAgeDays)
                .fetch()
                .rowsUpdated();

        return deleteOtps
                .flatMap(deleted -> deactivateUsers
                        .doOnNext(deactivated -> log.info(
                                "Cleanup finished: {} OTP rows deleted, {} stale unverified accounts deactivated",
                                deleted, deactivated)))
                // @Transactional is not applied at runtime in this service, so the TransactionalOperator is the real boundary
                .as(transactionalOperator::transactional)
                .then()
                .onErrorResume(e -> {
                    log.error("Cleanup failed: {}", e.getClass().getSimpleName());
                    return Mono.empty();
                });
    }
}
