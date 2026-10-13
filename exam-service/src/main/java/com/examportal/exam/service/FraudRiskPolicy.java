package com.examportal.exam.service;

import com.examportal.common.domain.entity.FraudEventType;
import com.examportal.common.domain.entity.RiskLevel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Fraud Risk Policy
 * The one place that turns the fraud events of a session into a risk level: every event type has
 * a weight, the weights of a session's events are added up, and the sum falls into a level.
 * Informational only: no score, status or penalty follows from it.
 *
 * Weights are fixed here; the level boundaries can be changed with exam.fraud.risk.*
 * (EXAM_FRAUD_RISK_MEDIUM / _HIGH / _CRITICAL). Boundaries that do not ascend fail the startup.
 */
@Component
public class FraudRiskPolicy {

    private static final Map<FraudEventType, Integer> WEIGHTS = new EnumMap<>(Map.of(
            FraudEventType.TAB_SWITCH, 1,
            FraudEventType.WINDOW_BLUR, 1,
            FraudEventType.FULLSCREEN_EXIT, 2,
            FraudEventType.COPY_ATTEMPT, 2,
            FraudEventType.PASTE_ATTEMPT, 2,
            FraudEventType.DEVTOOLS, 5,
            FraudEventType.OTHER, 1));

    private final int mediumFrom;
    private final int highFrom;
    private final int criticalFrom;

    public FraudRiskPolicy(@Value("${exam.fraud.risk.medium-from:5}") int mediumFrom,
                           @Value("${exam.fraud.risk.high-from:15}") int highFrom,
                           @Value("${exam.fraud.risk.critical-from:30}") int criticalFrom) {
        if (mediumFrom < 1 || highFrom <= mediumFrom || criticalFrom <= highFrom) {
            throw new IllegalStateException("exam.fraud.risk boundaries must ascend: 0 < medium < high < critical");
        }
        this.mediumFrom = mediumFrom;
        this.highFrom = highFrom;
        this.criticalFrom = criticalFrom;
    }

    public int weight(FraudEventType type) {
        return WEIGHTS.getOrDefault(type, 1);
    }

    /**
     * The weighted sum of a session's events, given how often each type occurred
     */
    public int score(Map<FraudEventType, ? extends Number> countsByType) {
        long score = 0;
        for (Map.Entry<FraudEventType, ? extends Number> entry : countsByType.entrySet()) {
            score += (long) weight(entry.getKey()) * entry.getValue().longValue();
        }
        return (int) Math.min(score, Integer.MAX_VALUE);
    }

    /**
     * LOW below the MEDIUM boundary (default 5), MEDIUM below HIGH (15), HIGH below CRITICAL (30)
     */
    public RiskLevel level(long score) {
        if (score >= criticalFrom) {
            return RiskLevel.CRITICAL;
        }
        if (score >= highFrom) {
            return RiskLevel.HIGH;
        }
        return score >= mediumFrom ? RiskLevel.MEDIUM : RiskLevel.LOW;
    }

    /**
     * The level as a number that sorts by severity: 0 = LOW ... 3 = CRITICAL
     */
    public RiskLevel levelOfRank(int rank) {
        return RiskLevel.values()[Math.max(0, Math.min(rank, RiskLevel.values().length - 1))];
    }

    /**
     * The level rank (0 = LOW ... 3 = CRITICAL) of a score as an SQL expression, for statements
     * that count or sort sessions by level. Built from the configured boundaries only.
     */
    public String levelRankSql(String scoreExpression) {
        return "(CASE WHEN " + scoreExpression + " >= " + criticalFrom + " THEN 3 WHEN " + scoreExpression + " >= "
                + highFrom + " THEN 2 WHEN " + scoreExpression + " >= " + mediumFrom + " THEN 1 ELSE 0 END)";
    }

    /**
     * The same weighted sum as an SQL aggregate over the given type column, for lists that compute
     * it per session in the database. Built from the enum and the weights only, never from input.
     */
    public String scoreSql(String typeColumn) {
        StringBuilder sql = new StringBuilder("COALESCE(SUM(CASE ").append(typeColumn);
        for (FraudEventType type : FraudEventType.values()) {
            sql.append(" WHEN '").append(type.name()).append("' THEN ").append(weight(type));
        }
        return sql.append(" ELSE 1 END), 0)").toString();
    }
}
