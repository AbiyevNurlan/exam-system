package com.examportal.common.domain.dto.internal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * User Stats Response DTO (service to service)
 * Aggregate counts only: no name, no email, no id of any user.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserStatsResponse {

    private long totalUsers;
    private long verifiedUsers;
    private long unverifiedUsers;
    private long lockedUsers;
    // Every day of the asked window, oldest first, days without a registration included
    private List<Day> registrationsPerDay;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Day {
        // yyyy-MM-dd, a calendar day in the configured day zone
        private String date;
        // Accounts created that day
        private long count;
        // Of those, how many have a verified email by now
        private long verifiedCount;
    }
}
