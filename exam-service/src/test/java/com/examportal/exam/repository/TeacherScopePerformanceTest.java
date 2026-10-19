package com.examportal.exam.repository;

import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B13: the owner-scoped statements of a teacher with 20 000 participants (in 40 own groups, some in two of them)
 * among 100 teachers, 2 000 groups, 120 000 memberships and 4 000 exams (throwaway Testcontainers database). Each
 * finishes within 300 ms over R2DBC (the second run, after one warm-up), the teacher's list of participants (the
 * ids of the own members and the group names of a page) as well; the plans and times are printed (B13-EXPLAIN).
 * Skipped without Docker.
 */
class TeacherScopePerformanceTest {

    private static final long LIMIT_MS = 300;

    private static PostgreSQLContainer<?> postgres;
    private static DatabaseClient db;
    private static GroupMembershipRepository membership;
    private static GroupOverviewRepository overview;
    private static ExamManageRepository manage;
    private static MyStatsRepository stats;
    private static UUID teacher;
    private static UUID member;

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
        membership = new GroupMembershipRepository(db);
        overview = new GroupOverviewRepository(db);
        manage = new ExamManageRepository(db);
        stats = new MyStatsRepository(db);

        for (String statement : List.of(
                "CREATE TABLE perf_owners AS SELECT g AS n, gen_random_uuid() AS id FROM generate_series(1, 100) g",
                "CREATE TABLE perf_users AS SELECT g AS n, gen_random_uuid() AS id FROM generate_series(1, 100000) g",
                "CREATE UNIQUE INDEX ON perf_users (n)",
                // Owner 1 is the teacher: groups 1..40; the other 99 owners share the other 1 960 groups
                "INSERT INTO user_groups (name, created_by, owner_id) SELECT 'Group ' || g, o.id, o.id "
                        + "FROM generate_series(1, 2000) g JOIN perf_owners o ON o.n = CASE WHEN g <= 40 THEN 1 ELSE 2 + g % 99 END",
                "CREATE TABLE perf_groups AS SELECT row_number() OVER (ORDER BY id) AS n, id FROM user_groups",
                // Users 1..20 000 in one of the teacher's groups, every fifth of them also in a second one
                "INSERT INTO user_group_members (group_id, user_id, source, added_by) "
                        + "SELECT (SELECT id FROM perf_groups WHERE n = 1 + u.n % 40), u.id, 'ADMIN', u.id FROM perf_users u WHERE u.n <= 20000",
                "INSERT INTO user_group_members (group_id, user_id, source, added_by) "
                        + "SELECT (SELECT id FROM perf_groups WHERE n = 1 + (u.n + 1) % 40), u.id, 'ADMIN', u.id FROM perf_users u "
                        + "WHERE u.n <= 20000 AND u.n % 5 = 0",
                // Everybody, the teacher's participants too, in a group of another owner
                "INSERT INTO user_group_members (group_id, user_id, source, added_by) "
                        + "SELECT (SELECT id FROM perf_groups WHERE n = 41 + u.n % 1960), u.id, 'ADMIN', u.id FROM perf_users u",
                "INSERT INTO exams (title, start_time, end_time, status, created_by, show_results_immediately) "
                        + "SELECT 'Exam ' || g, now() - interval '30 days', now() + interval '30 days', "
                        + "(ARRAY['DRAFT','ACTIVE','SCHEDULED','COMPLETED'])[1 + g % 4], (SELECT id FROM perf_owners WHERE n = 1 + g % 100), true "
                        + "FROM generate_series(1, 4000) g",
                "ANALYZE")) {
            db.sql(statement).then().block();
        }
        teacher = db.sql("SELECT id FROM perf_owners WHERE n = 1").map(row -> row.get("id", UUID.class)).one().block();
        member = db.sql("SELECT id FROM perf_users WHERE n = 4242").map(row -> row.get("id", UUID.class)).one().block();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static void explain(String name, String sql) {
        String plan = String.join("\n", db.sql("EXPLAIN (ANALYZE, BUFFERS) " + sql).bind("o", teacher)
                .map(row -> row.get(0, String.class)).all().collectList().block());
        System.out.println("B13-EXPLAIN " + name + "\n" + plan);
    }

    /** The median of five runs after one warm-up (connection, plan cache, JIT), in ms */
    private static <T> T timed(String name, Supplier<T> call) {
        T result = call.get();
        long[] runs = new long[5];
        for (int i = 0; i < runs.length; i++) {
            long started = System.nanoTime();
            result = call.get();
            runs[i] = (System.nanoTime() - started) / 1_000_000;
        }
        java.util.Arrays.sort(runs);
        System.out.println("B13-EXPLAIN " + name + ": median " + runs[2] + " ms, max " + runs[4] + " ms");
        assertThat(runs[2]).as(name).isLessThan(LIMIT_MS);
        return result;
    }

    @Test
    void the_statements_of_a_teacher_with_20000_participants_finish_within_300_ms() {
        explain("member ids of the owner", "SELECT DISTINCT m.user_id FROM user_groups g JOIN user_group_members m ON m.group_id = g.id "
                + "WHERE g.owner_id = :o AND g.active = true");

        List<UUID> ids = timed("member ids of the owner", () -> membership.findMemberIdsOfOwner(teacher).block());
        assertThat(ids).hasSize(20_000);

        List<UUID> page = ids.subList(0, 25);
        var groups = timed("group names of a page", () -> membership.findGroupsOfUsers(page, teacher).collectList().block());
        // Only the teacher's groups, never the other owner's group every user is in too
        assertThat(groups).extracting(GroupMembershipRepository.UserGroupName::groupName)
                .allMatch(name -> Integer.parseInt(name.substring("Group ".length())) <= 40);

        timed("participant list of the teacher in exam-service (ids and the group names of a page)", () -> {
            List<UUID> all = membership.findMemberIdsOfOwner(teacher).block();
            return membership.findGroupsOfUsers(all.subList(0, 25), teacher).collectList().block();
        });
        assertThat(membership.findMemberIdsOfOwner(UUID.randomUUID()).block()).isEmpty();

        assertThat(timed("member of an own group", () -> membership.isMemberOfOwner(member, teacher).block())).isTrue();
        assertThat(timed("own groups page", () -> overview.findPage("%", teacher, 25, 0).collectList().block())).hasSize(25);
        ExamManageRepository.Filter own = new ExamManageRepository.Filter(teacher, null, null);
        assertThat(timed("own exams count", () -> manage.count(own).block())).isEqualTo(40);
        assertThat(timed("own exams page", () -> manage.findPage(own, 25, 0).collectList().block())).hasSize(25);
        var teaching = timed("start flags", () -> stats.teaching(teacher).block());
        assertThat(teaching.hasGroups()).isTrue();
        assertThat(teaching.hasMembers()).isTrue();
        assertThat(teaching.hasExams()).isTrue();
    }
}
