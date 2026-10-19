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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B11b: group lists in Azerbaijani alphabetical order (az_ci, V15) on a real PostgreSQL with all Flyway
 * migrations: the admin list, the groups of one owner and the search keep their filters; the case does not
 * matter, equal names keep the order of their ids. Skipped without Docker.
 */
class GroupNameOrderTest {

    private static PostgreSQLContainer<?> postgres;
    private static DatabaseClient db;
    private static GroupOverviewRepository groups;

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
        groups = new GroupOverviewRepository(db);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static void group(String name, UUID owner) {
        db.sql("INSERT INTO user_groups (name, created_by, owner_id, active) VALUES (:name, :owner, :owner, true)")
                .bind("name", name).bind("owner", owner).then().block();
    }

    private static List<String> names(String pattern, UUID owner) {
        return groups.findPage(pattern, owner, 50, 0).map(GroupOverviewRepository.GroupFigures::name).collectList().block();
    }

    @Test
    void groupsInAzerbaijaniOrder() {
        UUID one = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        for (String name : List.of("Zoologiya", "Ədəbiyyat", "Şagirdlər", "Coğrafiya", "Çay klubu", " Elm", "İnformatika",
                "Işıqlı yol", "Xəttatlıq", "Qrup 2", "Kimya", "Öyrənənlər", "Üzgüçülük", "Həkimlər")) {
            group(name, one);
        }
        group("ədəbiyyat sevənlər", other);

        assertThat(names("%", null)).containsExactly("Coğrafiya", "Çay klubu", " Elm", "Ədəbiyyat", "ədəbiyyat sevənlər",
                "Həkimlər", "Xəttatlıq", "Işıqlı yol", "İnformatika", "Kimya", "Qrup 2", "Öyrənənlər", "Şagirdlər",
                "Üzgüçülük", "Zoologiya");
        // The groups of one owner, and a search, in the same order
        assertThat(names("%", other)).containsExactly("ədəbiyyat sevənlər");
        assertThat(names("ə%", null)).containsExactly("Ədəbiyyat", "ədəbiyyat sevənlər");
    }
}
