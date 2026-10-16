package com.examportal.exam.repository;

import com.examportal.common.domain.dto.MyStatsResponse;
import com.examportal.common.domain.dto.participant.ParticipantCardDtos;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B11: the exam-service part of the participant card on a real PostgreSQL with all Flyway migrations
 * (throwaway Testcontainers database): an instructor's view is limited to the own groups and exams, the
 * attempts are paged newest first, the events are those where the user acted or was the target, and the
 * statistics follow the rule of GET /api/me/stats over the exams of the scope. Skipped without Docker.
 */
class ParticipantCardRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 12, 0);

    private static PostgreSQLContainer<?> postgres;
    private static DatabaseClient db;
    private static ParticipantCardRepository cards;
    private static MyStatsRepository stats;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        db = DatabaseClient.create(new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
                .host(postgres.getHost()).port(postgres.getMappedPort(5432)).database(postgres.getDatabaseName())
                .username(postgres.getUsername()).password(postgres.getPassword()).build()));
        cards = new ParticipantCardRepository(db);
        stats = new MyStatsRepository(db);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static long group(String name, UUID owner, boolean active) {
        return db.sql("INSERT INTO user_groups (name, created_by, owner_id, active) VALUES (:name, :owner, :owner, :active) RETURNING id")
                .bind("name", name).bind("owner", owner).bind("active", active)
                .map(row -> row.get("id", Long.class)).one().block();
    }

    private static void member(long groupId, UUID user) {
        db.sql("INSERT INTO user_group_members (group_id, user_id, source, added_by) VALUES (:g, :u, 'ADMIN', :u)")
                .bind("g", groupId).bind("u", user).then().block();
    }

    private static long exam(String title, UUID creator) {
        return db.sql("""
                        INSERT INTO exams (title, start_time, end_time, status, created_by, show_results_immediately)
                        VALUES (:title, :start, :end, 'ACTIVE', :creator, true) RETURNING id
                        """)
                .bind("title", title).bind("start", NOW.minusDays(5)).bind("end", NOW.plusDays(5)).bind("creator", creator)
                .map(row -> row.get("id", Long.class)).one().block();
    }

    private static void session(long examId, UUID user, String percentage, int hoursAgo) {
        db.sql("""
                        INSERT INTO exam_sessions (session_id, exam_id, user_id, start_time, status, grading_status,
                                                   score, total_points, percentage, passed, submitted_at)
                        VALUES (:sid, :exam, :user, :start, 'COMPLETED', 'NOT_REQUIRED', 1, 2, :pct, true, :submitted)
                        """)
                .bind("sid", UUID.randomUUID().toString()).bind("exam", examId).bind("user", user)
                .bind("start", NOW.minusHours(hoursAgo + 1)).bind("pct", new BigDecimal(percentage)).bind("submitted", NOW.minusHours(hoursAgo))
                .then().block();
    }

    private static void event(UUID actor, String action, UUID target, int hoursAgo) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("INSERT INTO group_audit_log (actor_id, action, target_user_id, created_at) "
                        + "VALUES (:actor, :action, :target, :at)")
                .bind("actor", actor).bind("action", action).bind("at", NOW.minusHours(hoursAgo));
        (target == null ? spec.bindNull("target", UUID.class) : spec.bind("target", target)).then().block();
    }

    @Test
    void theCardOfOneParticipant() {
        UUID user = UUID.randomUUID();
        UUID teacher = UUID.randomUUID();
        UUID otherTeacher = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        long own = group("Own " + user, teacher, true);
        long foreign = group("Foreign " + user, otherTeacher, true);
        long deleted = group("Deleted " + user, teacher, false);
        member(own, user);
        member(foreign, user);
        member(deleted, user);

        long ownExam = exam("Own exam", teacher);
        long foreignExam = exam("Foreign exam", otherTeacher);
        session(ownExam, user, "40.00", 30);
        session(foreignExam, user, "90.00", 20);
        session(ownExam, user, "60.00", 10);

        event(admin, "MEMBER_ADDED", user, 3);
        event(user, "JOIN_REQUESTED", null, 2);
        event(user, "SELF_EVENT", user, 1);          // actor and target: listed once
        event(admin, "MEMBER_ADDED", UUID.randomUUID(), 0); // someone else's

        // Membership of an own active group decides an instructor's access
        assertThat(cards.isMemberOfGroupOwnedBy(user, teacher).block()).isTrue();
        assertThat(cards.isMemberOfGroupOwnedBy(user, UUID.randomUUID()).block()).isFalse();
        UUID stranger = UUID.randomUUID();
        member(deleted, stranger);
        assertThat(cards.isMemberOfGroupOwnedBy(stranger, teacher).block()).as("a deleted group gives no access").isFalse();

        // Groups: every active one for an administrator, the own ones for the instructor
        assertThat(cards.findGroups(user, null).block()).extracting(ParticipantCardDtos.Membership::getGroupId)
                .containsExactlyInAnyOrder(own, foreign);
        assertThat(cards.findGroups(user, teacher).block()).extracting(ParticipantCardDtos.Membership::getGroupId).containsExactly(own);

        // Attempts: newest first, paged; the instructor sees those at own exams only
        List<ParticipantCardDtos.SessionRow> all = cards.findSessions(user, null, 0, 2).block();
        assertThat(all).extracting(ParticipantCardDtos.SessionRow::getPercentage).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("60.00"), new BigDecimal("90.00"));
        assertThat(cards.findSessions(user, null, 1, 2).block()).hasSize(1);
        assertThat(cards.countSessions(user, null).block()).isEqualTo(3);
        assertThat(cards.findSessions(user, teacher, 0, 20).block()).extracting(ParticipantCardDtos.SessionRow::getExamTitle)
                .containsOnly("Own exam");
        assertThat(cards.countSessions(user, teacher).block()).isEqualTo(2);

        // Statistics: the rule of /api/me/stats, over every exam or the own exams of the instructor
        MyStatsResponse everything = stats.forUser(user, null, NOW).block();
        assertThat(everything.getExamsTaken()).isEqualTo(2);
        assertThat(everything.getBestPercent()).isEqualByComparingTo("90.00");
        MyStatsResponse ownOnly = stats.forUser(user, teacher, NOW).block();
        assertThat(ownOnly.getExamsTaken()).isEqualTo(1);
        assertThat(ownOnly.getBestPercent()).isEqualByComparingTo("60.00");
        assertThat(stats.forUser(user, NOW).block()).isEqualTo(everything);

        // Events where the user acted or was the target, newest first, nobody else's
        assertThat(cards.findEvents(user, 20).block()).extracting(AuditQueryRepository.Row::action)
                .containsExactly("SELF_EVENT", "JOIN_REQUESTED", "MEMBER_ADDED");
        assertThat(cards.findEvents(user, 2).block()).hasSize(2);
    }
}
