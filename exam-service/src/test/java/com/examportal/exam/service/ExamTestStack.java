package com.examportal.exam.service;

import com.examportal.exam.mapper.ExamMapper;
import com.examportal.exam.repository.*;
import com.examportal.exam.storage.MediaStorage;
import com.examportal.exam.structure.ExamStateRepository;
import com.examportal.exam.structure.ExamStructureWriter;
import com.examportal.exam.structure.StructurePlanner;
import com.examportal.exam.validator.ExamValidator;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import org.flywaydb.core.Flyway;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.mockito.Mockito.mock;

/**
 * The exam services wired against a throwaway PostgreSQL (Testcontainers, all Flyway migrations), with real
 * repositories, real transactions and only the outside world mocked (auth-service, media storage, results).
 * For tests of B12a that must see what really reaches the database.
 */
final class ExamTestStack implements AutoCloseable {

    final PostgreSQLContainer<?> postgres;
    final DatabaseClient db;
    final TransactionalOperator tx;
    final ExamRepository exams;
    final QuestionRepository questions;
    final QuestionOptionRepository options;
    final ExamSessionRepository sessions;
    final ExamBlockRepository blocks;
    final UserGroupRepository groups;
    final ExamAudienceRepository audience;
    final GroupAuditService groupAudit;
    final ExamAccessPolicy accessPolicy;
    final ExamMediaService examMedia;
    final ExamStateRepository states;
    final ExamStructureWriter writer;
    final ExamCacheService cache = new ExamCacheService();

    private ExamTestStack(PostgreSQLContainer<?> postgres) {
        this.postgres = postgres;
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        PostgresqlConnectionFactory factory = new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
                .host(postgres.getHost()).port(postgres.getMappedPort(5432)).database(postgres.getDatabaseName())
                .username(postgres.getUsername()).password(postgres.getPassword()).build());
        db = DatabaseClient.create(factory);
        tx = TransactionalOperator.create(new R2dbcTransactionManager(factory));
        R2dbcRepositoryFactory repositories = new R2dbcRepositoryFactory(new R2dbcEntityTemplate(factory));
        exams = repositories.getRepository(ExamRepository.class);
        questions = repositories.getRepository(QuestionRepository.class);
        options = repositories.getRepository(QuestionOptionRepository.class);
        sessions = repositories.getRepository(ExamSessionRepository.class);
        blocks = repositories.getRepository(ExamBlockRepository.class);
        groups = repositories.getRepository(UserGroupRepository.class);
        audience = repositories.getRepository(ExamAudienceRepository.class);
        groupAudit = new GroupAuditService(repositories.getRepository(GroupAuditLogRepository.class));
        accessPolicy = new ExamAccessPolicy(exams);
        MediaLinkRepository links = new MediaLinkRepository(db);
        examMedia = new ExamMediaService(repositories.getRepository(MediaAssetRepository.class), links, new MediaStorage());
        states = new ExamStateRepository(db);
        writer = new ExamStructureWriter(db, links);
    }

    static ExamTestStack start() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        return new ExamTestStack(postgres);
    }

    ExamService examService(ExamFeatures features) {
        return new ExamService(exams, questions, options, sessions, new ExamAttemptRepository(db), new ExamEditLock(db),
                new ExamManageRepository(db), new GroupMembershipRepository(db), new ExamDayClock("Asia/Baku"), cache,
                mock(AnswerSubmissionService.class), accessPolicy, groups, audience, groupAudit, examMedia,
                new ExamValidator(), new ExamMapper(), tx, states, new StructurePlanner(), writer, blocks, features);
    }

    long scalar(String sql) {
        return db.sql(sql).map(row -> ((Number) row.get(0)).longValue()).one().block();
    }

    @Override
    public void close() {
        postgres.stop();
    }
}
