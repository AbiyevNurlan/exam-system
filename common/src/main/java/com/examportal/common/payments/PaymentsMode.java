package com.examportal.common.payments;

import java.util.Locale;

/**
 * What the payment settings mean, the same rule in every service (exam-service's SandboxProductionGuard, auth-service's
 * /api/guest/feature). The sandbox is the provider whose test page takes any order as paid: no real money moves.
 *
 * Outside the Spring profile "production" nothing is decided here: PAYMENTS_SANDBOX_ALLOWED says whether it is on.
 * In production the sandbox runs only
 * - in DEMO mode: PAYMENTS_DEMO_MODE=true and PAYMENTS_PROVIDER=SANDBOX and PAYMENTS_SANDBOX_ALLOWED=true, all three, or
 * - with the older override PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION=I-UNDERSTAND (and PAYMENTS_SANDBOX_ALLOWED=true);
 * otherwise it is closed and hidden (its page and its notifications 404).
 */
public final class PaymentsMode {

    public static final String PRODUCTION_PROFILE = "production";
    public static final String SANDBOX = "SANDBOX";
    public static final String OVERRIDE = "I-UNDERSTAND";

    /** OFF, ON (development), DEMO (production demo), BLOCKED_IN_PRODUCTION, ON_IN_PRODUCTION (the older override) */
    public enum State { OFF, ON, DEMO, BLOCKED_IN_PRODUCTION, ON_IN_PRODUCTION }

    private final boolean production;
    private final boolean paidEnabled;
    private final boolean allowed;
    private final boolean demoMode;
    private final boolean sandboxProvider;
    private final boolean overridden;

    public PaymentsMode(boolean production, boolean paidEnabled, String provider, boolean allowed, boolean demoMode, String override) {
        this.production = production;
        this.paidEnabled = paidEnabled;
        this.allowed = allowed;
        this.demoMode = demoMode;
        this.sandboxProvider = provider != null && SANDBOX.equals(provider.trim().toUpperCase(Locale.ROOT));
        this.overridden = production && override != null && OVERRIDE.equals(override.trim());
    }

    public boolean production() {
        return production;
    }

    public boolean allowed() {
        return allowed;
    }

    public boolean demoMode() {
        return demoMode;
    }

    public boolean sandboxProvider() {
        return sandboxProvider;
    }

    public boolean overridden() {
        return overridden;
    }

    /** Production with all three switches of the demo */
    public boolean demoActive() {
        return production && demoMode && sandboxProvider && allowed;
    }

    /** Whether the sandbox may be on here at all (then PAYMENTS_SANDBOX_ALLOWED decides) */
    public boolean permitted() {
        return !production || overridden || demoActive();
    }

    /** In production without the demo or the override the sandbox does not exist: its page and its notifications 404 */
    public boolean hidden() {
        return !permitted();
    }

    /** The settings ask for the sandbox somehow: its switch, the demo, or the sandbox as checkout provider of open paid exams */
    public boolean requested() {
        return allowed || demoMode || (paidEnabled && sandboxProvider);
    }

    /** Production, the sandbox asked for, and it stays closed */
    public boolean misconfigured() {
        return production && requested() && !permitted();
    }

    /**
     * Misconfigured and contradictory: a switch that opens the sandbox (PAYMENTS_SANDBOX_ALLOWED or PAYMENTS_DEMO_MODE) is
     * set but the sandbox cannot run. Only PAYMENTS_PROVIDER=SANDBOX with nothing else is the expected closed state.
     */
    public boolean contradictory() {
        return misconfigured() && (allowed || demoMode);
    }

    /** The sandbox is on: it takes checkouts and notifications */
    public boolean sandboxOn() {
        return allowed && permitted();
    }

    /** The paid flow pays with the sandbox: the public sees a DEMO payment (no real money is charged) */
    public boolean paymentsDemo() {
        return paidEnabled && sandboxProvider && sandboxOn();
    }

    public State state() {
        if (!production) {
            return allowed ? State.ON : State.OFF;
        }
        if (demoActive()) {
            return State.DEMO;
        }
        if (overridden) {
            return allowed ? State.ON_IN_PRODUCTION : State.OFF;
        }
        return requested() ? State.BLOCKED_IN_PRODUCTION : State.OFF;
    }
}
