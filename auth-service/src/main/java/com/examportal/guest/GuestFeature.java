package com.examportal.guest;

import com.examportal.common.payments.PaymentsMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * PAID_ACCESS_ENABLED (default false): whether the guest sign-in of paid open exams is open at all. paymentsDemo: whether
 * the paid flow pays with the sandbox, a DEMO payment without real money (PaymentsMode, the same rule as exam-service's
 * SandboxProductionGuard; the same PAYMENTS_* settings reach both services), for the banner of the site.
 */
@Component
public class GuestFeature {

    private final boolean enabled;
    private final boolean paymentsDemo;

    public GuestFeature(boolean enabled) {
        this.enabled = enabled;
        this.paymentsDemo = false;
    }

    @Autowired
    public GuestFeature(Environment environment,
                        @Value("${features.paid-access.enabled:false}") boolean enabled,
                        @Value("${payments.provider:SANDBOX}") String provider,
                        @Value("${payments.sandbox.allowed:false}") boolean sandboxAllowed,
                        @Value("${payments.demo-mode:false}") boolean demoMode,
                        @Value("${payments.sandbox.allow-in-production:}") String override) {
        this.enabled = enabled;
        this.paymentsDemo = new PaymentsMode(environment.acceptsProfiles(Profiles.of(PaymentsMode.PRODUCTION_PROFILE)), enabled, provider,
                sandboxAllowed, demoMode, override).paymentsDemo();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean paymentsDemo() {
        return paymentsDemo;
    }
}
