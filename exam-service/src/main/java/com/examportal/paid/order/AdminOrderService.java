package com.examportal.paid.order;

import com.examportal.paid.PaidErrors;
import com.examportal.paid.exam.PaidExamRepository;
import com.examportal.paid.guest.GuestDirectory;
import com.examportal.paid.payment.PaidNotifications;
import com.examportal.paid.payment.PaymentEventRepository;
import com.examportal.paid.provider.ManualPaymentProvider;
import com.examportal.paid.web.PaidDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * B13b item 11: what an administrator does with orders and paid access (ADMIN only, every action recorded):
 * the list and one order with its payment events, the CSV of a filter, a payment outside the system (MANUAL), free
 * access to a paid exam, and the figures of a paid exam.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOrderService {

    private static final ZoneId BAKU = ZoneId.of("Asia/Baku");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final AdminOrderRepository list;
    private final OrderRepository orders;
    private final EntitlementRepository entitlements;
    private final PaymentEventRepository events;
    private final PaidExamRepository exams;
    private final GuestDirectory directory;
    private final PaidNotifications notifications;
    private final TransactionalOperator transactionalOperator;

    public Mono<PaidDtos.AdminOrderPage> page(AdminOrderRepository.Filter filter, String sortBy, String dir, int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), AdminOrderRepository.MAX_PAGE_SIZE);
        int pageNumber = Math.max(page, 0);
        return Mono.zip(list.count(filter), list.page(filter, sortBy, dir, pageSize, (long) pageNumber * pageSize)
                        .map(row -> PaidDtos.AdminOrderRow.of(row.order(), row.examTitle())).collectList())
                .map(found -> new PaidDtos.AdminOrderPage(found.getT2(), found.getT1(), pageNumber, pageSize));
    }

    public Mono<PaidDtos.AdminOrderDetail> detail(Long orderId) {
        return orders.find(orderId)
                .switchIfEmpty(Mono.error(PaidErrors.orderNotFound()))
                .flatMap(order -> Mono.zip(exams.find(order.examId()).map(exam -> exam.title()).defaultIfEmpty(""),
                                events.findOfOrder(orderId).collectList(),
                                entitlements.findOfGuest(order.guestId()).filter(entitlement -> orderId.equals(entitlement.orderId())).collectList())
                        .map(found -> new PaidDtos.AdminOrderDetail(PaidDtos.AdminOrderRow.of(order, found.getT1()), order.providerRef(),
                                order.reviewReason(), order.refundReason(), found.getT2(), found.getT3())));
    }

    /** The CSV of a filter: UTF-8 with a BOM, comma separated, Azerbaijani headers, times in Baku; at most 100 000 rows */
    public Mono<Flux<DataBuffer>> csv(AdminOrderRepository.Filter filter, String sortBy, String dir, UUID adminId) {
        return list.count(filter).flatMap(total -> {
            if (total > AdminOrderRepository.MAX_EXPORT_ROWS) {
                return Mono.error(PaidErrors.invalidFilter("filter", "more than " + AdminOrderRepository.MAX_EXPORT_ROWS
                        + " orders: narrow the filter"));
            }
            log.info("Orders exported by {}: {} rows", adminId, total);
            DefaultDataBufferFactory buffers = DefaultDataBufferFactory.sharedInstance;
            Flux<String> lines = Flux.concat(Flux.just("\uFEFF" + String.join(",", CSV_HEADERS) + "\r\n"),
                    list.page(filter, sortBy, dir, AdminOrderRepository.MAX_EXPORT_ROWS, 0).map(AdminOrderService::csvLine));
            return Mono.just(lines.map(line -> (DataBuffer) buffers.wrap(line.getBytes(StandardCharsets.UTF_8))));
        });
    }

    static final List<String> CSV_HEADERS = List.of("Sifariş nömrəsi", "İştirakçı kodu", "İmtahan ID", "İmtahan", "Məbləğ", "Məbləğ (qəpik)",
            "Valyuta", "Status", "Provayder", "Yaradılıb", "Ödənilib", "Geri qaytarılıb", "Demo");

    /** The last column: "bəli" for a DEMO order (no real money), "xeyr" for a real one */
    static String csvLine(AdminOrderRepository.Row row) {
        Order order = row.order();
        return String.join(",", cell(order.orderNo()), String.valueOf(order.guestCode()), String.valueOf(order.examId()), cell(row.examTitle()),
                cell(money(order.amountMinor())), String.valueOf(order.amountMinor()), order.currency(), order.status().name(), order.provider(),
                baku(order.createdAt()), baku(order.paidAt()), baku(order.refundedAt()), AdminOrderRepository.demo(order) ? "bəli" : "xeyr") + "\r\n";
    }

    static String money(int minor) {
        return (minor / 100) + "," + String.format("%02d", minor % 100);
    }

    static String baku(LocalDateTime utc) {
        return utc == null ? "" : utc.atZone(ZoneOffset.UTC).withZoneSameInstant(BAKU).format(TIME);
    }

    /** A CSV cell: quoted when it needs to be; a leading = + - @ is made harmless for spreadsheets */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String text = value;
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")
                ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }

    /**
     * A payment outside the system (cash, bank transfer): a reason and the document's number are required. A PENDING or
     * NEEDS_REVIEW order becomes PAID with its entitlement and provider MANUAL (real money, never DEMO), in one
     * transaction with a MANUAL payment event; an order
     * without a seat takes one first (409 SOLD_OUT), a guest entitled already is 409 ALREADY_ENTITLED.
     */
    public Mono<Order> markPaid(UUID adminId, Long orderId, String reasonText, String documentNo) {
        return Mono.defer(() -> {
            String reason = checkedReason(reasonText);
            if (documentNo == null || documentNo.isBlank() || documentNo.strip().length() > 100) {
                return Mono.error(PaidErrors.invalidFilter("documentNo", "is required (at most 100 characters)"));
            }
            String document = documentNo.strip();
            Mono<Order> mark = orders.lock(orderId)
                    .switchIfEmpty(Mono.error(PaidErrors.orderNotFound()))
                    .flatMap(order -> {
                        if (order.status() != OrderStatus.PENDING && order.status() != OrderStatus.NEEDS_REVIEW) {
                            return Mono.error(PaidErrors.transitionNotAllowed(order.status().name(), OrderStatus.PAID.name()));
                        }
                        Mono<Void> seat = order.seatHeld() ? Mono.empty()
                                : exams.takeSeat(order.examId()).flatMap(taken -> taken
                                ? orders.markSeatHeld(order.id())
                                : Mono.<Void>error(PaidErrors.soldOut()));
                        return seat.then(exams.find(order.examId()))
                                .flatMap(exam -> entitlements.grantForOrder(order, exam.attemptsPerPurchase()))
                                .switchIfEmpty(Mono.error(PaidErrors.alreadyEntitled()))
                                .then(orders.markManual(order.id()))
                                .then(orders.move(order.id(), OrderStatus.PAID, null))
                                .flatMap(paid -> events.insert(order.id(), ManualPaymentProvider.NAME, "manual-" + UUID.randomUUID(), "MANUAL_PAYMENT", true,
                                                order.amountMinor(), order.currency(), "PAID", "{\"adminId\":\"" + adminId + "\",\"reason\":"
                                                        + json(reason) + ",\"documentNo\":" + json(document) + "}")
                                        .thenReturn(paid));
                    });
            return transactionalOperator.transactional(mark)
                    .flatMap(paid -> notifications.paid(paid).then(notifications.event(paid, "ORDER_PAID_MANUALLY", "document " + document)).thenReturn(paid));
        });
    }

    /** Free access to a paid open exam for a guest (by id or by the 7-digit code), with a reason */
    public Mono<EntitlementRepository.Entitlement> grant(UUID adminId, Long examId, UUID guestId, Integer participantCode, String reasonText,
                                                        Integer attempts) {
        return Mono.defer(() -> {
            String reason = checkedReason(reasonText);
            int count = attempts == null ? 1 : attempts;
            if (count < 1 || count > 10) {
                return Mono.<EntitlementRepository.Entitlement>error(PaidErrors.invalidFilter("attempts", "must be from 1 to 10"));
            }
            if (guestId == null && participantCode == null) {
                return Mono.<EntitlementRepository.Entitlement>error(PaidErrors.invalidFilter("guestId", "guestId or participantCode is required"));
            }
            Mono<GuestDirectory.GuestRef> guest = guestId != null ? directory.get(guestId) : directory.byCode(participantCode);
            return exams.find(examId)
                    .filter(exam -> exam.paidOpen())
                    .switchIfEmpty(Mono.error(PaidErrors.examNotFound()))
                    .then(guest)
                    .flatMap(found -> entitlements.grantByAdmin(found.id(), examId, count, adminId, reason)
                            .switchIfEmpty(Mono.error(PaidErrors.alreadyEntitled()))
                            .flatMap(granted -> notifications.guestEvent(found.id(), found.participantCode(), "ENTITLEMENT_GRANTED",
                                    "exam " + examId + ", reason: " + reason).thenReturn(granted)));
        });
    }

    public Mono<PaidDtos.ExamSummary> summary(Long examId) {
        return exams.find(examId)
                .filter(exam -> exam.paidOpen())
                .switchIfEmpty(Mono.error(PaidErrors.examNotFound()))
                .flatMap(exam -> list.summary(examId).map(figures -> new PaidDtos.ExamSummary(examId, exam.title(), exam.currency(),
                        figures.paidCount(), figures.revenueMinor(), figures.refundedCount(), figures.refundedMinor(),
                        figures.refundPendingCount(), figures.needsReviewCount(), figures.pendingCount(), exam.seatLimit(), exam.seatsTaken(),
                        figures.demoPaidCount(), figures.demoAmountMinor())));
    }

    private static String checkedReason(String reason) {
        String trimmed = reason == null ? "" : reason.strip();
        if (trimmed.length() < 3 || trimmed.length() > 500) {
            throw PaidErrors.reasonRequired();
        }
        return trimmed;
    }

    private static String json(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
    }
}
