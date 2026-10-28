package com.examportal.exam.service;

import com.examportal.common.domain.dto.ManagedExamResponse;
import com.examportal.common.domain.dto.PageResponse;
import com.examportal.common.exception.ValidationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The management list narrowed to one access mode (B13b) on a real PostgreSQL: without the parameter it lists both
 * kinds as before, AUDIENCE and PAID_OPEN list only their own kind (ignoring case), anything else is refused; every row
 * names its access mode, a paid open exam also its price and sales window, an AUDIENCE exam none of it; an instructor
 * still sees only the own exams. Skipped without Docker.
 */
class ManagedListAccessModeTest {

    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID TEACHER = UUID.randomUUID();
    private static final String SEARCH = "T-b13b list";
    private static final LocalDateTime NOW = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    private static ExamTestStack stack;
    private static ExamService exams;
    private static long audience;
    private static long paid;
    private static long teachersAudience;

    @BeforeAll
    static void start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        stack = ExamTestStack.start();
        exams = stack.examService(new ExamFeatures(false, false));
        audience = exam(SEARCH + " audience", ADMIN);
        paid = exam(SEARCH + " paid", ADMIN);
        teachersAudience = exam(SEARCH + " teacher", TEACHER);
        stack.db.sql("UPDATE exams SET access_mode = 'PAID_OPEN', price_minor = 1250, seat_limit = 40, seats_taken = 3, "
                        + "sales_open_at = :open, sales_close_at = :close WHERE id = :id")
                .bind("open", NOW.minusHours(1)).bind("close", NOW.plusDays(1)).bind("id", paid).then().block();
    }

    @AfterAll
    static void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    private static long exam(String title, UUID owner) {
        return stack.db.sql("INSERT INTO exams (title, description, duration_minutes, start_time, end_time, status, total_questions, "
                        + "passing_score, max_attempts, created_by, access_type, active, created_at, updated_at, version) "
                        + "VALUES (:title, 'list test', 30, :start, :end, 'SCHEDULED', 0, 50, 1, :owner, 'PUBLIC', true, :now, :now, 0) RETURNING id")
                .bind("title", title).bind("start", NOW.plusDays(2)).bind("end", NOW.plusDays(2).plusHours(3))
                .bind("owner", owner).bind("now", NOW)
                .map(row -> row.get(0, Long.class)).one().block();
    }

    private static List<ManagedExamResponse> list(UUID user, String role, String accessMode) {
        PageResponse<ManagedExamResponse> page = exams.getManagedExams(user, role, SEARCH, null, accessMode, 0, 50).block();
        assertThat(page.getTotalElements()).isEqualTo(page.getContent().size());
        return page.getContent();
    }

    private static List<Long> ids(List<ManagedExamResponse> rows) {
        return rows.stream().map(ManagedExamResponse::getId).toList();
    }

    @Test
    void withoutTheParameterBothKindsAreListedAsBefore() {
        assertThat(ids(list(ADMIN, "ADMIN", null))).containsExactlyInAnyOrder(audience, paid, teachersAudience);
        assertThat(ids(list(ADMIN, "ADMIN", " "))).containsExactlyInAnyOrder(audience, paid, teachersAudience);
        // The old signature is the same list
        assertThat(ids(exams.getManagedExams(ADMIN, "ADMIN", SEARCH, null, 0, 50).block().getContent()))
                .containsExactlyInAnyOrder(audience, paid, teachersAudience);
    }

    @Test
    void eachModeListsOnlyItsOwnKind() {
        assertThat(ids(list(ADMIN, "ADMIN", "PAID_OPEN"))).containsExactly(paid);
        assertThat(ids(list(ADMIN, "ADMIN", "paid_open"))).containsExactly(paid);
        assertThat(ids(list(ADMIN, "ADMIN", "AUDIENCE"))).containsExactlyInAnyOrder(audience, teachersAudience);
    }

    @Test
    void anUnknownModeIsRefused() {
        assertThatThrownBy(() -> exams.getManagedExams(ADMIN, "ADMIN", null, null, "FREE", 0, 20).block())
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void aRowNamesItsModeAndOnlyAPaidOneItsSale() {
        List<ManagedExamResponse> rows = list(ADMIN, "ADMIN", null);
        ManagedExamResponse paidRow = rows.stream().filter(row -> row.getId() == paid).findFirst().orElseThrow();
        assertThat(paidRow.getAccessMode()).isEqualTo("PAID_OPEN");
        assertThat(paidRow.getPriceMinor()).isEqualTo(1250);
        assertThat(paidRow.getCurrency()).isEqualTo("AZN");
        assertThat(paidRow.getSeatLimit()).isEqualTo(40);
        assertThat(paidRow.getSeatsTaken()).isEqualTo(3);
        assertThat(paidRow.getSalesOpenAt()).isEqualTo(NOW.minusHours(1));
        assertThat(paidRow.getSalesCloseAt()).isEqualTo(NOW.plusDays(1));

        ManagedExamResponse audienceRow = rows.stream().filter(row -> row.getId() == audience).findFirst().orElseThrow();
        assertThat(audienceRow.getAccessMode()).isEqualTo("AUDIENCE");
        assertThat(audienceRow.getPriceMinor()).isNull();
        assertThat(audienceRow.getCurrency()).isNull();
        assertThat(audienceRow.getSeatLimit()).isNull();
        assertThat(audienceRow.getSeatsTaken()).isNull();
        assertThat(audienceRow.getSalesOpenAt()).isNull();
        assertThat(audienceRow.getSalesCloseAt()).isNull();
    }

    @Test
    void anInstructorStillSeesOnlyTheOwnExams() {
        assertThat(ids(list(TEACHER, "INSTRUCTOR", null))).containsExactly(teachersAudience);
        assertThat(ids(list(TEACHER, "INSTRUCTOR", "AUDIENCE"))).containsExactly(teachersAudience);
        assertThat(list(TEACHER, "INSTRUCTOR", "PAID_OPEN")).isEmpty();
    }
}
