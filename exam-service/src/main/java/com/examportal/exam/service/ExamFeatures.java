package com.examportal.exam.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Switches of B12a (application.yml, both off by default):
 *  - exam.blocks.rules-enabled (EXAM_BLOCK_RULES_ENABLED): the participant side of blocks (B12b) is in
 *    place. Until then an exam with block timers, a block without free navigation or returning to earlier
 *    blocks cannot be published (409 FEATURE_NOT_AVAILABLE): participants would get none of those rules.
 *  - exam.history.require-change-reason-after-publish (REQUIRE_CHANGE_REASON_AFTER_PUBLISH): a sensitive
 *    change of a SCHEDULED or ACTIVE exam needs changeReason (400 CHANGE_REASON_REQUIRED); otherwise the
 *    reason is optional.
 */
@Component
public class ExamFeatures {

    private final boolean blockRulesEnabled;
    private final boolean requireChangeReasonAfterPublish;

    public ExamFeatures(@Value("${exam.blocks.rules-enabled:false}") boolean blockRulesEnabled,
                        @Value("${exam.history.require-change-reason-after-publish:false}") boolean requireChangeReasonAfterPublish) {
        this.blockRulesEnabled = blockRulesEnabled;
        this.requireChangeReasonAfterPublish = requireChangeReasonAfterPublish;
    }

    public boolean blockRulesEnabled() {
        return blockRulesEnabled;
    }

    public boolean requireChangeReasonAfterPublish() {
        return requireChangeReasonAfterPublish;
    }
}
