package com.examportal.auth.repository;

import com.examportal.common.domain.dto.internal.UserStatsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * User Stats Repository
 * Aggregate user counts for the platform analytics. One statement; it returns numbers only, never
 * a row of a user.
 */
@Repository
@RequiredArgsConstructor
public class UserStatsRepository {

    private final DatabaseClient databaseClient;

    /**
     * The totals, and the accounts created per calendar day of the given zone for every day from
     * firstDay to lastDay (days without a registration included). fromUtc is the UTC moment
     * firstDay starts; the window is read through idx_users_created_at.
     */
    public Mono<UserStatsResponse> stats(String zone, LocalDate firstDay, LocalDate lastDay, LocalDateTime fromUtc) {
        return databaseClient.sql("""
                        SELECT t.total, t.verified, t.locked, d.day::date AS day,
                               COALESCE(r.registered, 0) AS registered, COALESCE(r.verified, 0) AS registered_verified
                        FROM (SELECT COUNT(*) AS total,
                                     COUNT(*) FILTER (WHERE email_verified) AS verified,
                                     COUNT(*) FILTER (WHERE account_locked) AS locked
                              FROM users) t
                        CROSS JOIN generate_series(CAST(:firstDay AS date), CAST(:lastDay AS date), interval '1 day') AS d(day)
                        LEFT JOIN (SELECT ((created_at AT TIME ZONE 'UTC') AT TIME ZONE :zone)::date AS day,
                                          COUNT(*) AS registered,
                                          COUNT(*) FILTER (WHERE email_verified) AS verified
                                   FROM users
                                   WHERE created_at >= :fromUtc
                                   GROUP BY 1) r ON r.day = d.day::date
                        ORDER BY d.day
                        """)
                .bind("zone", zone)
                .bind("firstDay", firstDay)
                .bind("lastDay", lastDay)
                .bind("fromUtc", fromUtc)
                .map(row -> new Object[]{
                        row.get("total", Long.class), row.get("verified", Long.class), row.get("locked", Long.class),
                        row.get("day", LocalDate.class), row.get("registered", Long.class),
                        row.get("registered_verified", Long.class)})
                .all()
                .collectList()
                .map(rows -> {
                    List<UserStatsResponse.Day> days = new ArrayList<>();
                    for (Object[] row : rows) {
                        days.add(UserStatsResponse.Day.builder()
                                .date(row[3].toString())
                                .count((Long) row[4])
                                .verifiedCount((Long) row[5])
                                .build());
                    }
                    // The totals are the same on every row; the window always has at least one day
                    long total = (Long) rows.get(0)[0];
                    long verified = (Long) rows.get(0)[1];
                    return UserStatsResponse.builder()
                            .totalUsers(total)
                            .verifiedUsers(verified)
                            .unverifiedUsers(total - verified)
                            .lockedUsers((Long) rows.get(0)[2])
                            .registrationsPerDay(days)
                            .build();
                });
    }
}
