package com.examportal.guest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * PAID_ACCESS_ENABLED (default false): whether the guest sign-in of paid open exams is open at all
 */
@Component
public class GuestFeature {

    private final boolean enabled;

    public GuestFeature(@Value("${features.paid-access.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
