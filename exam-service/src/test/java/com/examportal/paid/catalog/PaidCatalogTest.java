package com.examportal.paid.catalog;

import com.examportal.common.exception.ApplicationException;
import com.examportal.paid.PaidTestDatabase;
import com.examportal.paid.exam.PaidExamRepository;
import com.examportal.paid.web.PaidDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B13b item 2 on a real PostgreSQL: the catalog shows only PAID_OPEN exams that are published and on sale now,
 * nothing of a draft, an AUDIENCE exam, a deleted one or one whose sales are over or not yet open; the seats left;
 * and an exam of it has exactly the public fields, never a question, an answer, the owner or the audience.
 */
class PaidCatalogTest {

    private static PaidTestDatabase database;
    private static PaidCatalogService catalog;
    private static long onSale;
    private static long limited;

    @BeforeAll
    static void start() {
        database = PaidTestDatabase.start();
        catalog = new PaidCatalogService(new PaidExamRepository(database.db));
        onSale = database.paidExam("T-b13b on sale", 2500, null);
        limited = database.paidExam("T-b13b limited", 1000, 10);
        database.db.sql("UPDATE exams SET seats_taken = 3 WHERE id = " + limited).then().block();
        long draft = database.paidExam("T-b13b draft", 1000, null);
        database.db.sql("UPDATE exams SET status = 'DRAFT' WHERE id = " + draft).then().block();
        long over = database.paidExam("T-b13b sales over", 1000, null);
        database.db.sql("UPDATE exams SET sales_open_at = LOCALTIMESTAMP - interval '3 days', sales_close_at = LOCALTIMESTAMP - interval '1 hour' WHERE id = " + over).then().block();
        long later = database.paidExam("T-b13b sales later", 1000, null);
        database.db.sql("UPDATE exams SET sales_open_at = LOCALTIMESTAMP + interval '1 hour', sales_close_at = LOCALTIMESTAMP + interval '1 day' WHERE id = " + later).then().block();
        long deleted = database.paidExam("T-b13b deleted", 1000, null);
        database.db.sql("UPDATE exams SET active = false WHERE id = " + deleted).then().block();
        database.exam("T-b13b audience");
    }

    @AfterAll
    static void stop() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void onlyPaidOpenPublishedExamsOnSaleNow() {
        PaidDtos.CatalogPage page = catalog.page(0, 50).block();
        assertThat(page.content()).extracting(PaidDtos.CatalogExam::title).containsExactlyInAnyOrder("T-b13b on sale", "T-b13b limited");
        assertThat(page.totalElements()).isEqualTo(2);
        PaidDtos.CatalogExam seats = catalog.one(limited).block();
        assertThat(seats.seatsLeft()).isEqualTo(7);
        assertThat(seats.priceMinor()).isEqualTo(1000);
        assertThat(seats.currency()).isEqualTo("AZN");
        assertThat(catalog.one(onSale).block().seatsLeft()).isNull();
        for (long hidden : List.of(onSale + 2, onSale + 3, onSale + 4, onSale + 5, onSale + 6, 999_999L)) {
            assertThatThrownBy(() -> catalog.one(hidden).block()).satisfies(error ->
                    assertThat(((ApplicationException) error).getErrorCode()).isEqualTo("EXAM_NOT_FOUND"));
        }
        // At most 50 a page
        assertThat(catalog.page(0, 10_000).block().size()).isEqualTo(50);
    }

    @Test
    void anExamOfTheCatalogHasOnlyThePublicFields() throws Exception {
        ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        Map<?, ?> fields = json.readValue(json.writeValueAsString(catalog.one(onSale).block()), Map.class);
        TreeSet<String> names = new TreeSet<>();
        for (Iterator<?> it = fields.keySet().iterator(); it.hasNext(); ) {
            names.add(String.valueOf(it.next()));
        }
        assertThat(names).isSubsetOf("id", "title", "description", "startTime", "endTime", "durationMinutes", "priceMinor",
                "currency", "seatsLeft", "salesCloseAt", "paymentsDemo");
        assertThat(names).doesNotContain("questions", "createdBy", "accessType", "groupIds", "status", "seatsTaken");
    }
}
