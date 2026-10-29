package com.examportal.paid.provider;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * The health component paymentsSandbox (also alone, with its details, at /actuator/health/payments): UP with the
 * sandbox's state, or WARNING when production asked for the sandbox (closed by SandboxProductionGuard) or keeps it ON
 * with the override. WARNING is not one of the statuses the overall health is made of, so the service stays UP and
 * nothing restarts it: only the warning shows.
 */
@Component
@RequiredArgsConstructor
public class PaymentsSandboxHealthIndicator implements HealthIndicator {

    public static final Status WARNING = new Status("WARNING", SandboxProductionGuard.MESSAGE);

    private final SandboxProductionGuard guard;

    @Override
    public Health health() {
        SandboxProductionGuard.State state = guard.state();
        boolean warn = state == SandboxProductionGuard.State.BLOCKED_IN_PRODUCTION || state == SandboxProductionGuard.State.ON_IN_PRODUCTION;
        Health.Builder health = warn ? Health.status(WARNING) : Health.up();
        health.withDetail("sandbox", state.name());
        if (warn) {
            health.withDetail("warning", SandboxProductionGuard.MESSAGE);
        }
        return health.build();
    }
}
