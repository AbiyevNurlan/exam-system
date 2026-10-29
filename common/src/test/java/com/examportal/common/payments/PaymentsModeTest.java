package com.examportal.common.payments;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The payment rule of every service: in production the sandbox runs only in DEMO (PAYMENTS_DEMO_MODE, PAYMENTS_PROVIDER
 * =SANDBOX and PAYMENTS_SANDBOX_ALLOWED, all three) or with the older override; paymentsDemo only when the paid flow
 * really pays with it; outside production PAYMENTS_SANDBOX_ALLOWED alone decides, as before.
 */
class PaymentsModeTest {

    /** production, paid, provider, allowed, demo, override -> state, sandbox on, hidden, paymentsDemo, misconfigured, contradictory */
    private static void row(boolean production, boolean paid, String provider, boolean allowed, boolean demo, String override,
                            PaymentsMode.State state, boolean on, boolean hidden, boolean paymentsDemo, boolean misconfigured, boolean contradictory) {
        PaymentsMode mode = new PaymentsMode(production, paid, provider, allowed, demo, override);
        String what = "production=" + production + " paid=" + paid + " provider=" + provider + " allowed=" + allowed + " demo=" + demo
                + " override=" + override;
        assertThat(mode.state()).as(what).isEqualTo(state);
        assertThat(mode.sandboxOn()).as(what + " sandboxOn").isEqualTo(on);
        assertThat(mode.hidden()).as(what + " hidden").isEqualTo(hidden);
        assertThat(mode.paymentsDemo()).as(what + " paymentsDemo").isEqualTo(paymentsDemo);
        assertThat(mode.misconfigured()).as(what + " misconfigured").isEqualTo(misconfigured);
        assertThat(mode.contradictory()).as(what + " contradictory").isEqualTo(contradictory);
    }

    @Test
    void inProductionOnlyAllThreeSwitchesMakeTheDemo() {
        // The DEMO: all three
        row(true, true, "SANDBOX", true, true, null, PaymentsMode.State.DEMO, true, false, true, false, false);
        row(true, true, " sandbox ", true, true, "", PaymentsMode.State.DEMO, true, false, true, false, false);
        // The demo without its paid exams: the sandbox runs, nothing is paid with it
        row(true, false, "SANDBOX", true, true, null, PaymentsMode.State.DEMO, true, false, false, false, false);
        // One switch missing: closed, contradictory (an ERROR)
        row(true, true, "SANDBOX", false, true, null, PaymentsMode.State.BLOCKED_IN_PRODUCTION, false, true, false, true, true);
        row(true, true, "EXTERNAL", true, true, null, PaymentsMode.State.BLOCKED_IN_PRODUCTION, false, true, false, true, true);
        row(true, true, "SANDBOX", true, false, null, PaymentsMode.State.BLOCKED_IN_PRODUCTION, false, true, false, true, true);
        // The expected closed state: the sandbox as provider of open paid exams, nothing else (a WARN)
        row(true, true, "SANDBOX", false, false, null, PaymentsMode.State.BLOCKED_IN_PRODUCTION, false, true, false, true, false);
        // Nothing asked for
        row(true, false, "SANDBOX", false, false, null, PaymentsMode.State.OFF, false, true, false, false, false);
        row(true, true, "EXTERNAL", false, false, null, PaymentsMode.State.OFF, false, true, false, false, false);
    }

    @Test
    void theOlderOverrideStillWorks() {
        row(true, true, "SANDBOX", true, false, "I-UNDERSTAND", PaymentsMode.State.ON_IN_PRODUCTION, true, false, true, false, false);
        row(true, true, "SANDBOX", false, false, "I-UNDERSTAND", PaymentsMode.State.OFF, false, false, false, false, false);
        row(true, true, "SANDBOX", true, false, "i-understand", PaymentsMode.State.BLOCKED_IN_PRODUCTION, false, true, false, true, true);
        // The demo wins over the override
        row(true, true, "SANDBOX", true, true, "I-UNDERSTAND", PaymentsMode.State.DEMO, true, false, true, false, false);
    }

    @Test
    void outsideProductionTheSandboxSwitchAloneDecides() {
        row(false, true, "SANDBOX", true, false, null, PaymentsMode.State.ON, true, false, true, false, false);
        row(false, true, "SANDBOX", true, true, null, PaymentsMode.State.ON, true, false, true, false, false);
        row(false, true, "SANDBOX", false, true, null, PaymentsMode.State.OFF, false, false, false, false, false);
        row(false, true, "EXTERNAL", true, false, null, PaymentsMode.State.ON, true, false, false, false, false);
        row(false, false, "SANDBOX", true, false, null, PaymentsMode.State.ON, true, false, false, false, false);
        row(false, true, null, false, false, null, PaymentsMode.State.OFF, false, false, false, false, false);
    }
}
