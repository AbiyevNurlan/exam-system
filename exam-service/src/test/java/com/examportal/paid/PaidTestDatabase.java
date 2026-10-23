package com.examportal.paid;

import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import org.flywaydb.core.Flyway;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One PostgreSQL 16 with every exam-service migration for the tests of paid open exams (B13b), with a connection pool
 * (parallel orders and starts need many connections). Skips the test when no Docker is available.
 */
public final class PaidTestDatabase implements AutoCloseable {

    public final PostgreSQLContainer<?> postgres;
    public final DatabaseClient db;
    public final TransactionalOperator tx;
    private final ConnectionPool pool;

    private PaidTestDatabase(PostgreSQLContainer<?> postgres, ConnectionPool pool) {
        this.postgres = postgres;
        this.pool = pool;
        this.db = DatabaseClient.create(pool);
        this.tx = TransactionalOperator.create(new R2dbcTransactionManager(pool));
    }

    public static PaidTestDatabase start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine").withCommand("postgres", "-c", "max_connections=300");
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        PostgresqlConnectionFactory factory = new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
                .host(postgres.getHost()).port(postgres.getMappedPort(5432)).database(postgres.getDatabaseName())
                .username(postgres.getUsername()).password(postgres.getPassword()).build());
        ConnectionPool pool = new ConnectionPool(ConnectionPoolConfiguration.builder(factory)
                .initialSize(5).maxSize(100).maxAcquireTime(Duration.ofSeconds(60)).build());
        return new PaidTestDatabase(postgres, pool);
    }

    public long scalar(String sql) {
        return db.sql(sql).map(row -> ((Number) row.get(0)).longValue()).one().block();
    }

    /** An exam row, published (SCHEDULED), starting in two days; its id */
    public long exam(String title) {
        LocalDateTime now = LocalDateTime.now();
        return db.sql("INSERT INTO exams (title, description, duration_minutes, start_time, end_time, status, total_questions, "
                        + "passing_score, max_attempts, created_by, access_type, active, created_at, updated_at, version) "
                        + "VALUES (:title, 'B13b test', 30, :start, :end, 'SCHEDULED', 0, 50, 1, :owner, 'PUBLIC', true, :now, :now, 0) RETURNING id")
                .bind("title", title).bind("start", now.plusDays(2)).bind("end", now.plusDays(2).plusHours(3))
                .bind("owner", UUID.randomUUID()).bind("now", now)
                .map(row -> row.get(0, Long.class)).one().block();
    }

    @Override
    public void close() {
        pool.dispose();
        postgres.stop();
    }
}
