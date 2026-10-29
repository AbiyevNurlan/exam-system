package com.examportal.paid.provider;

import com.examportal.common.payments.PaymentsMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * The sandbox never runs on a live system by accident. A live system is the Spring profile "production" (the public
 * mode, docker-compose.public.yml, starts every service with it; development runs "docker" or none). The rule itself is
 * PaymentsMode (common): in production the sandbox runs only in DEMO mode (PAYMENTS_DEMO_MODE=true with
 * PAYMENTS_PROVIDER=SANDBOX and PAYMENTS_SANDBOX_ALLOWED=true) or with the older override
 * PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION=I-UNDERSTAND. Otherwise:
 * - the sandbox is closed whatever PAYMENTS_SANDBOX_ALLOWED says: no checkout, its own page and its notifications 404;
 * - the service still starts (every other exam keeps running). At every start the log says why: WARN for the expected
 *   closed state (PAYMENTS_PROVIDER=SANDBOX, nothing else), ERROR for a contradiction (PAYMENTS_SANDBOX_ALLOWED=true
 *   without the demo, or a demo without its other two switches); the health component paymentsSandbox warns.
 * DEMO: a WARN at every start, the health component DEMO, an audit row (SandboxProductionAudit). The override: an ERROR
 * at every start and its own audit row, as before. Outside production nothing changes.
 */
@Slf4j
@Component
public class SandboxProductionGuard {

    public static final String PRODUCTION_PROFILE = PaymentsMode.PRODUCTION_PROFILE;
    public static final String OVERRIDE = PaymentsMode.OVERRIDE;
    public static final String MESSAGE = "Sandbox payments are not allowed in production";
    public static final String DEMO_MESSAGE = "Payments DEMO mode is ON: no real money is charged";
    public static final String BLOCKED_MESSAGE = "PAYMENTS_PROVIDER=SANDBOX is blocked in production; set PAYMENTS_DEMO_MODE=true for "
            + "demo or configure a real provider";

    /** What the sandbox is on this system (health, log) */
    public enum State { OFF, ON, DEMO, BLOCKED_IN_PRODUCTION, ON_IN_PRODUCTION }

    private final PaymentsMode mode;

    @Autowired
    public SandboxProductionGuard(Environment environment,
                                  @Value("${payments.sandbox.allowed:false}") boolean allowed,
                                  @Value("${payments.provider:SANDBOX}") String provider,
                                  @Value("${features.paid-access.enabled:false}") boolean paidEnabled,
                                  @Value("${payments.sandbox.allow-in-production:}") String override,
                                  @Value("${payments.demo-mode:false}") boolean demoMode) {
        this(environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILE)), allowed, provider, paidEnabled, override, demoMode);
    }

    /** Without the demo switch (PAYMENTS_DEMO_MODE=false) */
    public SandboxProductionGuard(Environment environment, boolean allowed, String provider, boolean paidEnabled, String override) {
        this(environment, allowed, provider, paidEnabled, override, false);
    }

    /** Without the demo switch (PAYMENTS_DEMO_MODE=false) */
    public SandboxProductionGuard(boolean production, boolean allowed, String provider, boolean paidEnabled, String override) {
        this(production, allowed, provider, paidEnabled, override, false);
    }

    public SandboxProductionGuard(boolean production, boolean allowed, String provider, boolean paidEnabled, String override,
                                  boolean demoMode) {
        this.mode = new PaymentsMode(production, paidEnabled, provider, allowed, demoMode, override);
        String settings = "(PAYMENTS_DEMO_MODE=" + demoMode + ", PAYMENTS_PROVIDER=" + (provider == null ? "" : provider.trim())
                + ", PAYMENTS_SANDBOX_ALLOWED=" + allowed + ", PAID_ACCESS_ENABLED=" + paidEnabled + ")";
        if (mode.contradictory()) {
            String why = demoMode
                    ? "PAYMENTS_DEMO_MODE=true needs PAYMENTS_PROVIDER=SANDBOX and PAYMENTS_SANDBOX_ALLOWED=true"
                    : "PAYMENTS_SANDBOX_ALLOWED=true without PAYMENTS_DEMO_MODE=true (set PAYMENTS_DEMO_MODE=true for demo, or "
                    + "PAYMENTS_SANDBOX_ALLOWED=false)";
            log.error("{}: {}; the sandbox stays closed {}", MESSAGE, why, settings);
        } else if (mode.misconfigured()) {
            log.warn("{}; the sandbox stays closed {}", BLOCKED_MESSAGE, settings);
        } else if (state() == State.DEMO) {
            log.warn("{} {}", DEMO_MESSAGE, settings);
        } else if (state() == State.ON_IN_PRODUCTION) {
            log.error("{}, but PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION={} keeps it ON: anyone can pay with the test page, no money moves",
                    MESSAGE, OVERRIDE);
        }
    }

    /** Outside production: whatever the configuration says */
    public static SandboxProductionGuard development() {
        return new SandboxProductionGuard(false, false, null, false, null);
    }

    /** Whether the sandbox may be on here at all (then PAYMENTS_SANDBOX_ALLOWED decides) */
    public boolean permitted() {
        return mode.permitted();
    }

    /** In production without the demo or the override the sandbox does not exist: its page and its notifications 404 */
    public boolean hidden() {
        return mode.hidden();
    }

    /** Production, the sandbox asked for, and it stays closed */
    public boolean misconfigured() {
        return mode.misconfigured();
    }

    /** The paid flow pays with the sandbox: the public is told it is a DEMO payment (/api/public/paid-exams) */
    public boolean paymentsDemo() {
        return mode.paymentsDemo();
    }

    public State state() {
        return State.valueOf(mode.state().name());
    }
}
