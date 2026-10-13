package com.examportal.common.domain.entity;

/**
 * Risk Level Enumeration
 * How suspicious the fraud events of a session are, from their weighted sum (FraudRiskPolicy).
 * Informational only: nothing is decided from it automatically.
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
