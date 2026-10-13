package com.examportal.common.domain.entity;

/**
 * Fraud Event Type Enumeration
 * What a participant's browser can report during a session (session_fraud_events.type).
 */
public enum FraudEventType {
    TAB_SWITCH,
    WINDOW_BLUR,
    FULLSCREEN_EXIT,
    COPY_ATTEMPT,
    PASTE_ATTEMPT,
    DEVTOOLS,
    OTHER
}