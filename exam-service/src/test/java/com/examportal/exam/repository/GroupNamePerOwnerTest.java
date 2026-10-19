package com.examportal.exam.repository;

import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B13 (exam V18): a group name is unique within the active groups of one owner only, case ignored, on a real
 * PostgreSQL with all Flyway migrations. Two teachers can both have "11A"; one teacher cannot have it twice;
 * a deleted group frees its name. Skipped without Docker.
 */
class GroupNamePerOwnerTest {

    private static PostgreSQLContainer<?> postgres;
    private static DatabaseClient db;

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
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static Long group(String name, UUID owner) {
        return db.sql("INSERT INTO user_groups (name, created_by, owner_id, active) VALUES (:name, :owner, :owner, true) RETURNING id")
                .bind("name", name).bind("owner", owner).map(row -> row.get("id", Long.class)).one().block();
    }

    @Test
    void two_owners_may_use_the_same_name() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        group("11A", a);
        group("11a", b);
        assertThat(db.sql("SELECT count(*) AS n FROM user_groups WHERE lower(name) = '11a'")
                .map(row -> row.get("n", Long.class)).one().block()).isEqualTo(2L);
    }

    @Test
    void one_owner_cannot_have_a_name_twice_in_any_case() {
        UUID a = UUID.randomUUID();
        group("Riyaziyyat", a);
        assertThatThrownBy(() -> group("RIYAZIYYAT", a)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void a_deleted_group_frees_its_name() {
        UUID a = UUID.randomUUID();
        Long first = group("Kimya", a);
        db.sql("UPDATE user_groups SET active = false WHERE id = :id").bind("id", first).then().block();
        assertThat(group("Kimya", a)).isNotNull().isNotEqualTo(first);
    }

    @Test
    void the_global_index_is_gone_and_the_per_owner_one_exists() {
        List<String> indexes = db.sql("SELECT indexname FROM pg_indexes WHERE tablename = 'user_groups'")
                .map(row -> row.get("indexname", String.class)).all().collectList().block();
        assertThat(indexes).contains("uq_user_groups_owner_name").doesNotContain("uq_user_groups_name", "idx_user_groups_owner");
    }
}
