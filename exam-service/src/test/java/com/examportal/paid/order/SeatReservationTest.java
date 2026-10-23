package com.examportal.paid.order;

import com.examportal.common.exception.ApplicationException;
import com.examportal.paid.PaidTestDatabase;
import com.examportal.paid.exam.PaidExamRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B13b item 4 on a real PostgreSQL: 200 guests order at once for 50 seats, exactly 50 get an order and 150 are told
 * SOLD_OUT, and the exam counts 50 seats; one guest ordering 20 times at once gets one order; expired orders give their
 * seats back; closed sales, an exam that is not PAID_OPEN and an ACTIVE entitlement are refused. Skipped without Docker.
 */
class SeatReservationTest {

    private static PaidTestDatabase database;
    private static SeatReservation seats;

    @BeforeAll
    static void start() {
        database = PaidTestDatabase.start();
        OrderRepository orders = new OrderRepository(database.db);
        seats = new SeatReservation(new PaidExamRepository(database.db), orders, new EntitlementRepository(database.db), database.tx);
    }

    @AfterAll
    static void stop() {
        if (database != null) {
            database.close();
        }
    }

    private static String outcome(Mono<SeatReservation.Reservation> reservation) {
        return reservation.map(made -> made.created() ? "CREATED" : "EXISTING")
                .onErrorResume(ApplicationException.class, error -> Mono.just(error.getErrorCode()))
                .block();
    }

    @Test
    void twoHundredOrdersForFiftySeatsGiveExactlyFifty() {
        long exam = database.paidExam("T-b13b fifty seats", 1500, 50);
        List<String> outcomes = Flux.range(0, 200)
                .flatMap(i -> seats.reserve(UUID.randomUUID(), 1_000_000 + i, exam, "SANDBOX")
                        .map(made -> "CREATED")
                        .onErrorResume(ApplicationException.class, error -> Mono.just(error.getErrorCode()))
                        .subscribeOn(Schedulers.parallel()), 200)
                .collectList().block();
        Map<String, Long> counts = outcomes.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertThat(counts).containsEntry("CREATED", 50L).containsEntry("SOLD_OUT", 150L).hasSize(2);
        assertThat(database.scalar("SELECT count(*) FROM orders WHERE exam_id = " + exam)).isEqualTo(50);
        assertThat(database.scalar("SELECT seats_taken FROM exams WHERE id = " + exam)).isEqualTo(50);
        assertThat(database.scalar("SELECT count(*) FROM orders WHERE exam_id = " + exam + " AND amount_minor = 1500 AND seat_held")).isEqualTo(50);

        // Their time is over: the next order expires them, gets a seat, and the count is right again
        database.db.sql("UPDATE orders SET expires_at = LOCALTIMESTAMP - interval '1 second' WHERE exam_id = " + exam).then().block();
        assertThat(outcome(seats.reserve(UUID.randomUUID(), 1_234_567, exam, "SANDBOX"))).isEqualTo("CREATED");
        assertThat(database.scalar("SELECT count(*) FROM orders WHERE exam_id = " + exam + " AND status = 'EXPIRED' AND NOT seat_held")).isEqualTo(50);
        assertThat(database.scalar("SELECT seats_taken FROM exams WHERE id = " + exam)).isEqualTo(1);
    }

    @Test
    void oneGuestOrderingManyTimesAtOnceGetsOneOrder() {
        long exam = database.paidExam("T-b13b one guest", 1500, 5);
        UUID guest = UUID.randomUUID();
        List<String> outcomes = Flux.range(0, 20)
                .flatMap(i -> Mono.fromCallable(() -> outcome(seats.reserve(guest, 1_234_567, exam, "SANDBOX"))).subscribeOn(Schedulers.boundedElastic()), 20)
                .collectList().block();
        assertThat(outcomes).containsOnly("CREATED", "EXISTING").filteredOn("CREATED"::equals).hasSize(1);
        assertThat(database.scalar("SELECT count(*) FROM orders WHERE exam_id = " + exam)).isEqualTo(1);
        assertThat(database.scalar("SELECT seats_taken FROM exams WHERE id = " + exam)).isEqualTo(1);
    }

    @Test
    void closedSalesOtherExamsAndEntitledGuestsAreRefused() {
        long closed = database.paidExam("T-b13b closed", 1500, null);
        database.db.sql("UPDATE exams SET sales_open_at = LOCALTIMESTAMP - interval '2 days', sales_close_at = LOCALTIMESTAMP - interval '1 minute' WHERE id = " + closed)
                .then().block();
        assertThat(outcome(seats.reserve(UUID.randomUUID(), 1_234_567, closed, "SANDBOX"))).isEqualTo("SALES_CLOSED");
        long draft = database.paidExam("T-b13b draft", 1500, null);
        database.db.sql("UPDATE exams SET status = 'DRAFT' WHERE id = " + draft).then().block();
        assertThat(outcome(seats.reserve(UUID.randomUUID(), 1_234_567, draft, "SANDBOX"))).isEqualTo("SALES_CLOSED");
        long audience = database.exam("T-b13b audience");
        assertThat(outcome(seats.reserve(UUID.randomUUID(), 1_234_567, audience, "SANDBOX"))).isEqualTo("EXAM_NOT_FOUND");
        assertThat(outcome(seats.reserve(UUID.randomUUID(), 1_234_567, 999_999L, "SANDBOX"))).isEqualTo("EXAM_NOT_FOUND");

        long open = database.paidExam("T-b13b entitled", 1500, null);
        UUID guest = UUID.randomUUID();
        database.db.sql("INSERT INTO entitlements (guest_id, exam_id, status, attempts_left, granted_by, grant_reason) VALUES ('"
                + guest + "', " + open + ", 'ACTIVE', 1, '" + UUID.randomUUID() + "', 'test')").then().block();
        assertThat(outcome(seats.reserve(guest, 1_234_567, open, "SANDBOX"))).isEqualTo("ALREADY_ENTITLED");
        assertThat(database.scalar("SELECT seats_taken FROM exams WHERE id = " + open)).isZero();
        assertThatThrownBy(() -> seats.reserve(guest, 1_234_567, open, "SANDBOX").block()).isInstanceOf(ApplicationException.class);
    }
}
