package com.examportal.paid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * PAID_ACCESS_ENABLED (default false, the same switch as the guest sign-in of B13a): whether paid open exams exist at
 * all in exam-service. Off: every paid endpoint answers 404 FEATURE_DISABLED and the reconcile job does nothing.
 */
@Component
public class PaidFeature {

    private final boolean enabled;

    public PaidFeature(@Value("${features.paid-access.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
