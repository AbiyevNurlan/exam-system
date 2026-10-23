package com.examportal.guest;

import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import org.flywaydb.core.Flyway;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One PostgreSQL 16 with every auth-service migration for the guest tests (B13a): R2DBC for the code under test,
 * JDBC-free. Skips the test when no Docker is available.
 */
final class GuestTestDatabase implements AutoCloseable {

    final PostgreSQLContainer<?> postgres;
    final DatabaseClient db;
    final TransactionalOperator tx;

    private GuestTestDatabase(PostgreSQLContainer<?> postgres, DatabaseClient db, TransactionalOperator tx) {
        this.postgres = postgres;
        this.db = db;
        this.tx = tx;
    }

    static GuestTestDatabase start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        PostgresqlConnectionFactory factory = new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
                .host(postgres.getHost()).port(postgres.getMappedPort(5432)).database(postgres.getDatabaseName())
                .username(postgres.getUsername()).password(postgres.getPassword()).build());
        return new GuestTestDatabase(postgres, DatabaseClient.create(factory),
                TransactionalOperator.create(new R2dbcTransactionManager(factory)));
    }

    long scalar(String sql) {
        return db.sql(sql).map(row -> ((Number) row.get(0)).longValue()).one().block();
    }

    void account(String email) {
        db.sql("INSERT INTO users (email, password_hash, first_name, last_name, role) VALUES (:email, 'x', 'F', 'L', 'STUDENT')")
                .bind("email", email).then().block();
    }

    @Override
    public void close() {
        postgres.stop();
    }
}
