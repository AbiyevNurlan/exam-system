package com.examportal.paid.exam;

import com.examportal.common.domain.entity.ExamStatus;
import com.examportal.exam.history.ChangeContext;
import com.examportal.exam.history.ExamHistoryService;
import com.examportal.exam.history.RevisionDraft;
import com.examportal.paid.PaidErrors;
import com.examportal.paid.web.PaidDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * B13b item 1: the paid settings of an exam, ADMIN only. PAID_OPEN needs a price above zero, a sales window that ends
 * before the exam starts, a currency of the list (AZN), 0-720 refund hours, 1-10 attempts per purchase and a seat
 * limit not below the seats held now. The access mode changes only while the exam has no attempt (B9,
 * EXAM_HAS_SESSIONS) and, back to AUDIENCE, no open order (PAID_EXAM_HAS_ORDERS). A new price applies to new orders
 * only: an order keeps its own amount. Every change goes into the exam's history (B12a, SETTINGS_CHANGED), with the
 * exam row locked so a parallel save or order sees one or the other.
 */
@Service
@RequiredArgsConstructor
public class PaidExamSettingsService {

    public static final int MAX_PRICE_MINOR = 100_000_000;
    public static final int MAX_REFUND_HOURS = 720;

    private final PaidExamRepository exams;
    private final PaidOrderGuard orders;
    private final ExamHistoryService history;
    private final TransactionalOperator transactionalOperator;

    @Value("${payments.currencies:AZN}")
    private List<String> currencies = List.of("AZN");

    public Mono<PaidExamSettings> get(Long examId) {
        return exams.find(examId).switchIfEmpty(Mono.error(PaidErrors.examNotFound()));
    }

    public Mono<PaidExamSettings> update(Long examId, PaidDtos.SettingsRequest request, ChangeContext change) {
        Mono<PaidExamSettings> update = exams.lock(examId)
                .switchIfEmpty(Mono.error(PaidErrors.examNotFound()))
                .flatMap(before -> Mono.defer(() -> {
                    String mode = request.accessMode() == null ? before.accessMode() : request.accessMode().trim().toUpperCase(Locale.ROOT);
                    if (!PaidExamSettings.AUDIENCE.equals(mode) && !PaidExamSettings.PAID_OPEN.equals(mode)) {
                        return Mono.error(PaidErrors.invalidSettings("accessMode must be AUDIENCE or PAID_OPEN"));
                    }
                    if (before.status() == ExamStatus.COMPLETED || before.status() == ExamStatus.CANCELLED
                            || before.status() == ExamStatus.ARCHIVED) {
                        return Mono.error(PaidErrors.examClosed());
                    }
                    Integer price = request.priceMinor();
                    String currency = request.currency() == null ? before.currency() : request.currency().trim().toUpperCase(Locale.ROOT);
                    int refundHours = request.refundDeadlineHours() == null ? before.refundDeadlineHours() : request.refundDeadlineHours();
                    int attempts = request.attemptsPerPurchase() == null ? before.attemptsPerPurchase() : request.attemptsPerPurchase();
                    if (PaidExamSettings.PAID_OPEN.equals(mode)) {
                        if (price == null || price < 1 || price > MAX_PRICE_MINOR) {
                            return Mono.error(PaidErrors.invalidSettings("priceMinor must be a whole number of minor units from 1 to " + MAX_PRICE_MINOR));
                        }
                        if (!currencies.contains(currency)) {
                            return Mono.error(PaidErrors.invalidSettings("currency must be one of " + currencies));
                        }
                        if (request.salesOpenAt() == null || request.salesCloseAt() == null || !request.salesOpenAt().isBefore(request.salesCloseAt())) {
                            return Mono.error(PaidErrors.invalidSettings("salesOpenAt must be before salesCloseAt"));
                        }
                        if (before.startTime() == null || request.salesCloseAt().isAfter(before.startTime())) {
                            return Mono.error(PaidErrors.invalidSettings("the sales window must end before the exam starts"));
                        }
                        if (request.seatLimit() != null && (request.seatLimit() < 1 || request.seatLimit() < before.seatsTaken())) {
                            return Mono.error(PaidErrors.invalidSettings("seatLimit must be at least 1 and not below the seats held now ("
                                    + before.seatsTaken() + ")"));
                        }
                    }
                    if (refundHours < 0 || refundHours > MAX_REFUND_HOURS) {
                        return Mono.error(PaidErrors.invalidSettings("refundDeadlineHours must be from 0 to " + MAX_REFUND_HOURS));
                    }
                    if (attempts < 1 || attempts > 10) {
                        return Mono.error(PaidErrors.invalidSettings("attemptsPerPurchase must be from 1 to 10"));
                    }
                    boolean modeChanges = !mode.equals(before.accessMode());
                    Mono<Void> checks = Mono.empty();
                    if (modeChanges) {
                        // B9: what decided the attempts stays; a paid exam with open orders stays paid
                        checks = exams.hasSessions(examId).flatMap(taken -> taken ? Mono.<Void>error(PaidErrors.hasSessions()) : Mono.empty())
                                .then(PaidExamSettings.AUDIENCE.equals(mode)
                                        ? orders.hasOpenOrders(examId).flatMap(open -> open ? Mono.<Void>error(PaidErrors.hasOrders()) : Mono.empty())
                                        : Mono.empty());
                    }
                    boolean paid = PaidExamSettings.PAID_OPEN.equals(mode);
                    return checks
                            .then(exams.save(examId, mode, paid ? price : before.priceMinor(), currency,
                                    paid ? request.seatLimit() : before.seatLimit(),
                                    paid ? request.salesOpenAt() : before.salesOpenAt(), paid ? request.salesCloseAt() : before.salesCloseAt(),
                                    refundHours, attempts))
                            .flatMap(after -> {
                                List<RevisionDraft> drafts = diff(before, after);
                                return history.record(examId, before.ownerId(), before.status(), change, drafts).thenReturn(after);
                            });
                }));
        return transactionalOperator.transactional(update);
    }

    /** One SETTINGS_CHANGED row with every paid field that changed; none when nothing did */
    static List<RevisionDraft> diff(PaidExamSettings before, PaidExamSettings after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        put(changes, "accessMode", before.accessMode(), after.accessMode());
        put(changes, "priceMinor", before.priceMinor(), after.priceMinor());
        put(changes, "currency", before.currency(), after.currency());
        put(changes, "seatLimit", before.seatLimit(), after.seatLimit());
        put(changes, "salesOpenAt", time(before.salesOpenAt()), time(after.salesOpenAt()));
        put(changes, "salesCloseAt", time(before.salesCloseAt()), time(after.salesCloseAt()));
        put(changes, "refundDeadlineHours", before.refundDeadlineHours(), after.refundDeadlineHours());
        put(changes, "attemptsPerPurchase", before.attemptsPerPurchase(), after.attemptsPerPurchase());
        if (changes.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("changedFields", new ArrayList<>(changes.keySet()));
        params.put("accessMode", after.accessMode());
        return List.of(new RevisionDraft("SETTINGS_CHANGED", RevisionDraft.SETTINGS, after.examId(), changes, true,
                "PAID_SETTINGS_CHANGED", params));
    }

    private static void put(Map<String, Object> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            List<Object> pair = new ArrayList<>();
            pair.add(before);
            pair.add(after);
            changes.put(field, pair);
        }
    }

    private static String time(LocalDateTime time) {
        return time == null ? null : time.toString();
    }
}
