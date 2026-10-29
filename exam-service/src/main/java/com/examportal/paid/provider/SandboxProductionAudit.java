package com.examportal.paid.provider;

import com.examportal.exam.service.OwnershipPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * A sandbox kept ON in production with PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION=I-UNDERSTAND leaves one row in
 * group_audit_log at every start (GET /api/admin/audit, action PAYMENTS_SANDBOX_IN_PRODUCTION, actor: nobody, the
 * system). Nothing else is ever written here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxProductionAudit {

    public static final String ACTION = "PAYMENTS_SANDBOX_IN_PRODUCTION";

    private final SandboxProductionGuard guard;
    private final DatabaseClient db;

    @EventListener(ApplicationReadyEvent.class)
    public void atStart() {
        record().subscribe(null, error -> log.error("The audit row of the sandbox in production was not written: {}",
                error.getClass().getSimpleName()));
    }

    /** The audit row, only while the sandbox runs in production */
    public Mono<Void> record() {
        if (guard.state() != SandboxProductionGuard.State.ON_IN_PRODUCTION) {
            return Mono.empty();
        }
        return db.sql("INSERT INTO group_audit_log (actor_id, action, details, created_at) VALUES (:actor, :action, :details, :now)")
                .bind("actor", OwnershipPolicy.NOBODY)
                .bind("action", ACTION)
                .bind("details", "sandbox payments ON in production (PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION=" + SandboxProductionGuard.OVERRIDE
                        + "): the test page takes any order as paid, no money moves")
                .bind("now", LocalDateTime.now())
                .then();
    }
}
