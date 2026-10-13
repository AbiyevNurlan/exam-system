package com.examportal.exam.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Exam Day Clock
 * Where one day ends and the next begins for the daily attempt limit. The day is a calendar day in
 * the configured zone (exam.day-zone, EXAM_DAY_ZONE); every value handed out is UTC, like all dates
 * in this service. An unknown zone id fails the startup.
 */
@Component
public class ExamDayClock {

    private final ZoneId zone;

    public ExamDayClock(@Value("${exam.day-zone:Asia/Baku}") String zone) {
        this.zone = ZoneId.of(zone);
    }

    /**
     * The day that contains the given UTC moment: [start, end) in UTC
     */
    public Day dayOf(LocalDateTime utcNow) {
        LocalDateTime start = utcNow.atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(zone)
                .toLocalDate()
                .atStartOfDay(zone)
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
        LocalDateTime end = start.atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(zone)
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(zone)
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
        return new Day(start, end);
    }

    public Day today() {
        return dayOf(LocalDateTime.now());
    }

    /**
     * The zone id, for statements that group UTC timestamps by day of this zone
     */
    public String zoneId() {
        return zone.getId();
    }

    /**
     * The last `days` days up to and including today: the calendar days in the zone, and the UTC
     * moment the first of them starts
     */
    public Window lastDays(int days) {
        LocalDate lastDay = LocalDateTime.now().atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDate();
        LocalDate firstDay = lastDay.minusDays(days - 1L);
        LocalDateTime fromUtc = firstDay.atStartOfDay(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        return new Window(firstDay, lastDay, fromUtc);
    }

    public record Window(LocalDate firstDay, LocalDate lastDay, LocalDateTime fromUtc) {
    }

    public record Day(LocalDateTime start, LocalDateTime end) {
    }
}
