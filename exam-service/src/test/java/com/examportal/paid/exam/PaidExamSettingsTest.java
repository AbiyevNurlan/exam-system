package com.examportal.paid.exam;

import com.examportal.common.domain.entity.Exam;
import com.examportal.common.domain.entity.ExamAccessType;
import com.examportal.common.exception.ApplicationException;
import com.examportal.exam.history.ChangeContext;
import com.examportal.exam.history.ExamHistoryService;
import com.examportal.exam.history.RevisionDraft;
import com.examportal.exam.repository.ExamRepository;
import com.examportal.exam.service.ExamAccessPolicy;
import com.examportal.paid.PaidTestDatabase;
import com.examportal.paid.web.PaidDtos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B13b item 1 on a real PostgreSQL: the rules of the paid settings (price above zero in whole minor units, AZN, the
 * sales window before the start, the seat limit not below the seats held, refund hours, attempts), the access mode
 * fixed once the exam has attempts (B9) or open orders, an order keeping its price, the history row of every change;
 * and that a PAID_OPEN exam is open to nobody through the audience but ADMIN and its owner, in Java and in SQL, while
 * AUDIENCE exams stay as they were. Skipped without Docker.
 */
class PaidExamSettingsTest {

    private static PaidTestDatabase database;
    private static ExamRepository examRepository;
    private final ExamHistoryService history = mock(ExamHistoryService.class);
    private PaidExamSettingsService service;

    @BeforeAll
    static void start() {
        database = PaidTestDatabase.start();
        examRepository = new R2dbcRepositoryFactory(new R2dbcEntityTemplate(
                (io.r2dbc.spi.ConnectionFactory) org.springframework.test.util.ReflectionTestUtils.getField(database, "pool")))
                .getRepository(ExamRepository.class);
    }

    @AfterAll
    static void stop() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void setUp() {
        when(history.record(any(), any(), any(), any(), anyList())).thenReturn(Mono.empty());
        service = new PaidExamSettingsService(new PaidExamRepository(database.db), new PaidOrderGuard(database.db), history, database.tx);
    }

    private static PaidDtos.SettingsRequest paid(int price, LocalDateTime open, LocalDateTime close, Integer seats) {
        return new PaidDtos.SettingsRequest("PAID_OPEN", price, "AZN", seats, open, close, 24, 1, null);
    }

    private static String code(Throwable error) {
        return ((ApplicationException) error).getErrorCode();
    }

    @Test
    void theRulesOfAPaidExam() {
        long id = database.exam("T-b13b settings");
        LocalDateTime now = LocalDateTime.now();
        ChangeContext admin = ChangeContext.of(UUID.randomUUID(), "ADMIN");
        for (PaidDtos.SettingsRequest wrong : List.of(
                paid(0, now, now.plusDays(1), null),
                paid(-5, now, now.plusDays(1), null),
                new PaidDtos.SettingsRequest("PAID_OPEN", 1500, "USD", null, now, now.plusDays(1), 24, 1, null),
                paid(1500, now.plusDays(1), now, null),
                // The exam starts in two days: the sales may not end after that
                paid(1500, now, now.plusDays(3), null),
                paid(1500, now, now.plusDays(1), 0),
                new PaidDtos.SettingsRequest("PAID_OPEN", 1500, "AZN", null, now, now.plusDays(1), 721, 1, null),
                new PaidDtos.SettingsRequest("PAID_OPEN", 1500, "AZN", null, now, now.plusDays(1), 24, 11, null),
                new PaidDtos.SettingsRequest("SOMETHING", 1500, "AZN", null, now, now.plusDays(1), 24, 1, null))) {
            assertThatThrownBy(() -> service.update(id, wrong, admin).block()).as(String.valueOf(wrong))
                    .satisfies(error -> assertThat(code(error)).isEqualTo("PAID_SETTINGS_INVALID"));
        }
        PaidExamSettings saved = service.update(id, paid(2550, now, now.plusDays(1), 40), admin).block();
        assertThat(saved.paidOpen()).isTrue();
        assertThat(saved.priceMinor()).isEqualTo(2550);
        assertThat(saved.currency()).isEqualTo("AZN");
        assertThat(saved.seatLimit()).isEqualTo(40);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RevisionDraft>> drafts = ArgumentCaptor.forClass(List.class);
        verify(history).record(eq(id), any(), any(), eq(admin), drafts.capture());
        RevisionDraft draft = drafts.getValue().get(0);
        assertThat(draft.action()).isEqualTo("SETTINGS_CHANGED");
        assertThat(draft.entityType()).isEqualTo(RevisionDraft.SETTINGS);
        assertThat(draft.changes()).containsKeys("accessMode", "priceMinor", "seatLimit", "salesOpenAt", "salesCloseAt");
    }

    @Test
    void theAccessModeIsFixedOnceThereAreAttemptsOrOpenOrders() {
        long withSession = database.exam("T-b13b with a session");
        database.db.sql("INSERT INTO exam_sessions (session_id, exam_id, user_id, start_time, end_time, status, created_at, updated_at, version) "
                + "VALUES ('" + UUID.randomUUID() + "', " + withSession + ", '" + UUID.randomUUID() + "', LOCALTIMESTAMP, LOCALTIMESTAMP, 'COMPLETED', "
                + "LOCALTIMESTAMP, LOCALTIMESTAMP, 0)").then().block();
        LocalDateTime now = LocalDateTime.now();
        assertThatThrownBy(() -> service.update(withSession, paid(1000, now, now.plusDays(1), null), ChangeContext.of(UUID.randomUUID(), "ADMIN")).block())
                .satisfies(error -> assertThat(code(error)).isEqualTo("EXAM_HAS_SESSIONS"));

        long withOrder = database.paidExam("T-b13b with an order", 1000, null);
        database.db.sql("INSERT INTO orders (order_no, guest_id, guest_code, exam_id, amount_minor, currency, provider, expires_at) "
                + "VALUES ('T-o1', '" + UUID.randomUUID() + "', 1234567, " + withOrder + ", 1000, 'AZN', 'SANDBOX', LOCALTIMESTAMP + interval '1 hour')").then().block();
        assertThatThrownBy(() -> service.update(withOrder, new PaidDtos.SettingsRequest("AUDIENCE", null, null, null, null, null, null, null, null),
                ChangeContext.of(UUID.randomUUID(), "ADMIN")).block()).satisfies(error -> assertThat(code(error)).isEqualTo("PAID_EXAM_HAS_ORDERS"));

        // A new price: the order keeps its own
        service.update(withOrder, paid(3000, now, now.plusDays(1), null), ChangeContext.of(UUID.randomUUID(), "ADMIN")).block();
        assertThat(database.scalar("SELECT amount_minor FROM orders WHERE order_no = 'T-o1'")).isEqualTo(1000);
        assertThat(database.scalar("SELECT price_minor FROM exams WHERE id = " + withOrder)).isEqualTo(3000);
    }

    @Test
    void aPaidExamIsOpenOnlyToAdminAndOwnerThroughTheAudience() {
        long audience = database.exam("T-b13b audience public");
        long paidId = database.paidExam("T-b13b paid", 1500, null);
        UUID student = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        List<Long> forStudent = examRepository.findAvailableExams(student, false, false, now, 1000, 0).map(Exam::getId).collectList().block();
        assertThat(forStudent).contains(audience).doesNotContain(paidId);
        List<Long> forAdmin = examRepository.findAvailableExams(UUID.randomUUID(), true, false, now, 1000, 0).map(Exam::getId).collectList().block();
        assertThat(forAdmin).contains(audience, paidId);

        // The same rule in Java: the entity reads access_mode, a student is refused, the owner and ADMIN are not
        Exam paid = examRepository.findById(paidId).block();
        assertThat(paid.getAccessMode()).isEqualTo("PAID_OPEN");
        ExamAccessPolicy policy = new ExamAccessPolicy(examRepository, null);
        assertThat(policy.canAccess(student, "STUDENT", paid).block()).isFalse();
        assertThat(policy.canAccess(UUID.randomUUID(), "ADMIN", paid).block()).isTrue();
        assertThat(policy.canAccess(paid.getCreatedBy(), "INSTRUCTOR", paid).block()).isTrue();
        Exam open = examRepository.findById(audience).block();
        assertThat(open.getAccessMode()).isEqualTo("AUDIENCE");
        assertThat(open.getAccessType()).isEqualTo(ExamAccessType.PUBLIC);
        assertThat(policy.canAccess(student, "STUDENT", open).block()).isTrue();

        // Saving the entity never writes access_mode: an ordinary save keeps the exam paid
        paid.setTitle("T-b13b paid, renamed");
        examRepository.save(paid).block();
        assertThat(examRepository.findById(paidId).block().getAccessMode()).isEqualTo("PAID_OPEN");
    }
}
