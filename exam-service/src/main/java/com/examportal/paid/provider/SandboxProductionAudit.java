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
 * A sandbox that runs in production leaves one row in group_audit_log at every start (GET /api/admin/audit, actor:
 * nobody, the system): PAYMENTS_DEMO_MODE_ON in DEMO mode, PAYMENTS_SANDBOX_IN_PRODUCTION with the older override
 * PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION=I-UNDERSTAND. Nothing else is ever written here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxProductionAudit {

    public static final String ACTION = "PAYMENTS_SANDBOX_IN_PRODUCTION";
    public static final String DEMO_ACTION = "PAYMENTS_DEMO_MODE_ON";

    private final SandboxProductionGuard guard;
    private final DatabaseClient db;

    @EventListener(ApplicationReadyEvent.class)
    public void atStart() {
        record().subscribe(null, error -> log.error("The audit row of the sandbox in production was not written: {}",
                error.getClass().getSimpleName()));
    }

    /** The audit row, only while the sandbox runs in production: the DEMO mode, or the older override */
    public Mono<Void> record() {
        if (guard.state() == SandboxProductionGuard.State.DEMO) {
            return insert(DEMO_ACTION, "payments DEMO mode ON in production (PAYMENTS_DEMO_MODE=true): orders are paid on the demo page, "
                    + "no real money is charged");
        }
        if (guard.state() != SandboxProductionGuard.State.ON_IN_PRODUCTION) {
            return Mono.empty();
        }
        return insert(ACTION, "sandbox payments ON in production (PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION=" + SandboxProductionGuard.OVERRIDE
                + "): the test page takes any order as paid, no money moves");
    }

    private Mono<Void> insert(String action, String details) {
        return db.sql("INSERT INTO group_audit_log (actor_id, action, details, created_at) VALUES (:actor, :action, :details, :now)")
                .bind("actor", OwnershipPolicy.NOBODY)
                .bind("action", action)
                .bind("details", details)
                .bind("now", LocalDateTime.now())
                .then();
    }
}
