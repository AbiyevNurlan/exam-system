package com.examportal.paid.web;

import com.examportal.paid.exam.PaidExamSettings;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * The request and answer bodies of paid open exams (B13b). Money is always a whole number of minor units
 * (priceMinor, amountMinor: qəpik) with its currency; never a fraction.
 */
public final class PaidDtos {

    private PaidDtos() {
    }

    /** PUT /api/admin/paid-exams/{id}/settings; changeReason goes into the exam's history (B12a) */
    public record SettingsRequest(
            String accessMode,
            Integer priceMinor,
            @Size(min = 3, max = 3) String currency,
            Integer seatLimit,
            LocalDateTime salesOpenAt,
            LocalDateTime salesCloseAt,
            Integer refundDeadlineHours,
            Integer attemptsPerPurchase,
            @Size(max = 500) String changeReason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SettingsResponse(
            Long examId,
            String title,
            String status,
            String accessMode,
            Integer priceMinor,
            String currency,
            Integer seatLimit,
            int seatsTaken,
            LocalDateTime salesOpenAt,
            LocalDateTime salesCloseAt,
            int refundDeadlineHours,
            int attemptsPerPurchase) {

        public static SettingsResponse of(PaidExamSettings settings) {
            return new SettingsResponse(settings.examId(), settings.title(), settings.status() == null ? null : settings.status().name(),
                    settings.accessMode(), settings.priceMinor(), settings.currency(), settings.seatLimit(), settings.seatsTaken(),
                    settings.salesOpenAt(), settings.salesCloseAt(), settings.refundDeadlineHours(), settings.attemptsPerPurchase());
        }
    }

    /**
     * One exam of the public catalog: what a visitor needs to decide, nothing else (no question, answer, owner, audience
     * or internal field). seatsLeft is null when the exam has no seat limit.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CatalogExam(Long id, String title, String description, LocalDateTime startTime, LocalDateTime endTime,
                              Integer durationMinutes, int priceMinor, String currency, Integer seatsLeft,
                              LocalDateTime salesCloseAt) {
    }

    public record CatalogPage(java.util.List<CatalogExam> content, long totalElements, int page, int size) {
    }
}
