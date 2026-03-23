package com.examportal.common.utils;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Date Time Utility
 * Provides common date and time operations.
 */
public final class DateTimeUtils {

    private static final DateTimeFormatter ISO_DATE_TIME = DateTimeFormatter.ISO_DATE_TIME;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("UTC");

    private DateTimeUtils() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(DEFAULT_ZONE);
    }

    public static LocalDateTime nowAtZone(ZoneId zoneId) {
        return LocalDateTime.now(zoneId);
    }

    public static Instant nowInstant() {
        return Instant.now();
    }

    public static long toEpochMillis(LocalDateTime dateTime) {
        return dateTime.atZone(DEFAULT_ZONE).toInstant().toEpochMilli();
    }

    public static LocalDateTime fromEpochMillis(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), DEFAULT_ZONE);
    }

    public static String formatDateTime(LocalDateTime dateTime) {
        return dateTime.format(DATE_TIME_FORMAT);
    }

    public static String formatDate(LocalDate date) {
        return date.format(DATE_FORMAT);
    }

    public static String formatTime(LocalTime time) {
        return time.format(TIME_FORMAT);
    }

    public static String formatIso(LocalDateTime dateTime) {
        return dateTime.format(ISO_DATE_TIME);
    }

    public static LocalDateTime parseDateTime(String dateTimeStr) {
        return LocalDateTime.parse(dateTimeStr, DATE_TIME_FORMAT);
    }

    public static LocalDate parseDate(String dateStr) {
        return LocalDate.parse(dateStr, DATE_FORMAT);
    }

    public static long minutesBetween(LocalDateTime start, LocalDateTime end) {
        return ChronoUnit.MINUTES.between(start, end);
    }

    public static long secondsBetween(LocalDateTime start, LocalDateTime end) {
        return ChronoUnit.SECONDS.between(start, end);
    }

    public static boolean isExpired(LocalDateTime expirationTime) {
        return now().isAfter(expirationTime);
    }

    public static boolean isWithinRange(LocalDateTime dateTime, LocalDateTime start, LocalDateTime end) {
        return !dateTime.isBefore(start) && !dateTime.isAfter(end);
    }

    public static LocalDateTime addMinutes(LocalDateTime dateTime, long minutes) {
        return dateTime.plusMinutes(minutes);
    }

    public static LocalDateTime addHours(LocalDateTime dateTime, long hours) {
        return dateTime.plusHours(hours);
    }

    public static LocalDateTime addDays(LocalDateTime dateTime, long days) {
        return dateTime.plusDays(days);
    }

    public static Duration durationBetween(LocalDateTime start, LocalDateTime end) {
        return Duration.between(start, end);
    }

    public static String formatDuration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
}
