package com.examportal.paid.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * The sandbox never runs on a live system by accident. A live system is the Spring profile "production" (the public
 * mode, docker-compose.public.yml, starts every service with it; development runs "docker" or none). There, without
 * PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION=I-UNDERSTAND:
 * - the sandbox is closed whatever PAYMENTS_SANDBOX_ALLOWED says: no checkout, its own page and its notifications 404;
 * - the service still starts (every other exam keeps running); a sandbox that was asked for (PAYMENTS_SANDBOX_ALLOWED=true,
 *   or PAYMENTS_PROVIDER=SANDBOX with PAID_ACCESS_ENABLED=true) is an ERROR in the log at every start and a WARNING in
 *   the health component paymentsSandbox.
 * With the override the sandbox works as in development, with an ERROR at every start and an audit row
 * (SandboxProductionAudit). Outside production nothing changes.
 */
@Slf4j
@Component
public class SandboxProductionGuard {

    public static final String PRODUCTION_PROFILE = "production";
    public static final String OVERRIDE = "I-UNDERSTAND";
    public static final String MESSAGE = "Sandbox payments are not allowed in production";

    /** What the sandbox is on this system (health, log) */
    public enum State { OFF, ON, BLOCKED_IN_PRODUCTION, ON_IN_PRODUCTION }

    private final boolean production;
    private final boolean overridden;
    private final boolean requested;
    private final boolean allowed;

    @Autowired
    public SandboxProductionGuard(Environment environment,
                                  @Value("${payments.sandbox.allowed:false}") boolean allowed,
                                  @Value("${payments.provider:SANDBOX}") String provider,
                                  @Value("${features.paid-access.enabled:false}") boolean paidEnabled,
                                  @Value("${payments.sandbox.allow-in-production:}") String override) {
        this(environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILE)), allowed, provider, paidEnabled, override);
    }

    public SandboxProductionGuard(boolean production, boolean allowed, String provider, boolean paidEnabled, String override) {
        this.production = production;
        this.allowed = allowed;
        this.overridden = production && override != null && OVERRIDE.equals(override.trim());
        this.requested = allowed || (paidEnabled && provider != null
                && SandboxPaymentProvider.NAME.equals(provider.trim().toUpperCase(Locale.ROOT)));
        if (misconfigured()) {
            log.error("{}: the sandbox stays closed (PAYMENTS_SANDBOX_ALLOWED={}, PAYMENTS_PROVIDER={}, PAID_ACCESS_ENABLED={}); "
                    + "set PAYMENTS_SANDBOX_ALLOWED=false and a real provider", MESSAGE, allowed, provider == null ? "" : provider.trim(), paidEnabled);
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
        return !production || overridden;
    }

    /** In production without the override the sandbox does not exist: its page and its notifications 404 */
    public boolean hidden() {
        return !permitted();
    }

    /** Production, no override, and the configuration asks for the sandbox */
    public boolean misconfigured() {
        return production && !overridden && requested;
    }

    public State state() {
        if (!production) {
            return allowed ? State.ON : State.OFF;
        }
        if (!overridden) {
            return requested ? State.BLOCKED_IN_PRODUCTION : State.OFF;
        }
        return allowed ? State.ON_IN_PRODUCTION : State.OFF;
    }
}
