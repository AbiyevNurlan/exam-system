package com.examportal.auth.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.examportal.auth.client.InstructorFiguresClient;
import com.examportal.auth.repository.InstructorQueryRepository;
import com.examportal.auth.repository.UserRepository;
import com.examportal.common.domain.dto.instructor.InstructorDtos;
import com.examportal.common.exception.ApplicationException;
import com.examportal.common.security.TokenRevocationStore;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B13, item 7, on a real PostgreSQL with all auth-service migrations: an administrator makes a teacher (INSTRUCTOR,
 * active, verified, must change the password, temporary password for 7 days); the temporary password is answered
 * once and is in no table and no log line, only its hash; the same email again is 409 with nothing written; the list
 * with and without the figures of exam-service; deactivation needs confirm=true with running exams (also when
 * exam-service cannot tell), ends the sessions (access tokens first) and keeps the account; a reset gives a new
 * temporary password and ends the sessions; nothing changes when the access tokens cannot be ended; another
 * account than a teacher is 404. Skipped without Docker.
 */
class InstructorAdminServiceTest {

    private static final UUID ADMIN = UUID.randomUUID();
    private static PostgreSQLContainer<?> postgres;
    private static DatabaseClient db;
    private static UserRepository users;
    private static InstructorQueryRepository queries;
    private static TransactionalOperator tx;

    private final TokenService tokenService = mock(TokenService.class);
    private final TokenRevocationStore revocations = mock(TokenRevocationStore.class);
    private final InstructorFiguresClient figures = mock(InstructorFiguresClient.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private InstructorAdminService service;
    private ListAppender<ILoggingEvent> logs;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        PostgresqlConnectionFactory factory = new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
                .host(postgres.getHost()).port(postgres.getMappedPort(5432)).database(postgres.getDatabaseName())
                .username(postgres.getUsername()).password(postgres.getPassword()).build());
        db = DatabaseClient.create(factory);
        tx = TransactionalOperator.create(new R2dbcTransactionManager(factory));
        users = new R2dbcRepositoryFactory(new R2dbcEntityTemplate(factory)).getRepository(UserRepository.class);
        queries = new InstructorQueryRepository(db);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void setUp() {
        service = new InstructorAdminService(users, queries, tokenService, revocations, figures, encoder,
                new AccountAuditLog(db), tx);
        when(tokenService.revokeAllUserTokens(any())).thenReturn(Mono.empty());
        when(revocations.revokeAllIssuedBefore(any(), any())).thenReturn(Mono.empty());
        when(figures.figures(anyCollection())).thenReturn(Mono.just(Optional.empty()));
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
    }

    private static InstructorDtos.CreateRequest request(String email) {
        return InstructorDtos.CreateRequest.builder().firstName(" Aysel ").lastName("Quliyeva").email(email).build();
    }

    private static String code(Throwable error) {
        return ((ApplicationException) error).getErrorCode();
    }

    private static long scalar(String sql) {
        return db.sql(sql).map(row -> ((Number) row.get(0)).longValue()).one().block();
    }

    private void passwordIsNowhere(String password) {
        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains(password));
        assertThat(scalar("SELECT count(*) FROM admin_audit_log WHERE coalesce(details, '') LIKE '%" + password + "%'")).isZero();
        assertThat(scalar("SELECT count(*) FROM users WHERE password_hash = '" + password + "' OR first_name = '" + password + "'")).isZero();
    }

    @Test
    void a_new_teacher_with_a_temporary_password_answered_once() {
        InstructorDtos.Created created = service.create(request("New.Teacher@X.test"), ADMIN).block();

        assertThat(created.getRole()).isEqualTo("INSTRUCTOR");
        assertThat(created.getEmail()).isEqualTo("new.teacher@x.test");
        assertThat(created.getFirstName()).isEqualTo("Aysel");
        assertThat(created.getParticipantCode()).isBetween(1_000_000, 9_999_999);
        assertThat(created.isMustChangePassword()).isTrue();
        assertThat(created.getTemporaryPassword()).hasSize(16).matches(".*[A-Z].*").matches(".*[a-z].*").matches(".*\\d.*");
        assertThat(created.getTemporaryPasswordExpiresAt()).isCloseTo(LocalDateTime.now().plusDays(7), within(1, java.time.temporal.ChronoUnit.MINUTES));

        var stored = users.findById(created.getId()).block();
        assertThat(stored.getRole().name()).isEqualTo("INSTRUCTOR");
        assertThat(stored.isActive()).isTrue();
        assertThat(stored.isEmailVerified()).isTrue();
        assertThat(stored.isMustChangePassword()).isTrue();
        assertThat(encoder.matches(created.getTemporaryPassword(), stored.getPasswordHash())).isTrue();
        assertThat(scalar("SELECT count(*) FROM admin_audit_log WHERE action = 'INSTRUCTOR_CREATED' AND admin_user_id = '"
                + ADMIN + "' AND target_user_id = '" + created.getId() + "'")).isEqualTo(1);
        passwordIsNowhere(created.getTemporaryPassword());

        // The list shows the teacher, without a password, and without the figures when exam-service cannot tell
        var page = service.list(null, String.valueOf(created.getParticipantCode()), 0, 25).block();
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getStatus()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
        assertThat(page.getContent().get(0).isFiguresAvailable()).isFalse();
        assertThat(page.getContent().get(0).getGroupCount()).isNull();
    }

    @Test
    void the_same_email_again_is_409_and_nothing_is_written() {
        service.create(request("twice@x.test"), ADMIN).block();
        long audit = scalar("SELECT count(*) FROM admin_audit_log");
        assertThatThrownBy(() -> service.create(request("TWICE@x.test"), ADMIN).block())
                .satisfies(e -> {
                    assertThat(code(e)).isEqualTo("EMAIL_ALREADY_EXISTS");
                    assertThat(((ApplicationException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        assertThat(scalar("SELECT count(*) FROM users WHERE email = 'twice@x.test'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM admin_audit_log")).isEqualTo(audit);
    }

    @Test
    void deactivation_with_running_exams_needs_confirmation_and_ends_every_session() {
        UUID teacher = service.create(request("busy@x.test"), ADMIN).block().getId();
        InstructorDtos.UpdateRequest off = InstructorDtos.UpdateRequest.builder().active(false).build();

        // exam-service cannot tell: like running exams
        assertThatThrownBy(() -> service.update(teacher, off, false, ADMIN).block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("INSTRUCTOR_HAS_ACTIVE_EXAMS"));
        when(figures.figures(anyCollection())).thenReturn(Mono.just(Optional.of(Map.of(teacher,
                InstructorDtos.Figures.builder().id(teacher).examCount(3).activeExamCount(2).build()))));
        assertThatThrownBy(() -> service.update(teacher, off, false, ADMIN).block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("INSTRUCTOR_HAS_ACTIVE_EXAMS"));
        assertThat(users.findById(teacher).block().isActive()).isTrue();
        verify(revocations, never()).revokeAllIssuedBefore(eq(teacher), any());

        InstructorDtos.Row row = service.update(teacher, off, true, ADMIN).block();
        assertThat(row.isActive()).isFalse();
        assertThat(row.getStatus()).isEqualTo("DEACTIVATED");
        assertThat(row.getExamCount()).isEqualTo(3);
        verify(revocations).revokeAllIssuedBefore(eq(teacher), any());
        verify(tokenService).revokeAllUserTokens(teacher);
        assertThat(scalar("SELECT count(*) FROM admin_audit_log WHERE action = 'INSTRUCTOR_DEACTIVATED' AND target_user_id = '" + teacher + "'"))
                .isEqualTo(1);
        // The account and its content stay
        assertThat(scalar("SELECT count(*) FROM users WHERE id = '" + teacher + "'")).isEqualTo(1);

        // Without running exams no confirmation is needed; activating again
        assertThat(service.update(teacher, InstructorDtos.UpdateRequest.builder().active(true).lastName("Əliyeva").build(), false, ADMIN)
                .block().isActive()).isTrue();
        assertThat(users.findById(teacher).block().getLastName()).isEqualTo("Əliyeva");
    }

    @Test
    void nothing_changes_when_the_sessions_cannot_be_ended() {
        UUID teacher = service.create(request("redis-down@x.test"), ADMIN).block().getId();
        String hashBefore = users.findById(teacher).block().getPasswordHash();
        reset(revocations);
        when(revocations.revokeAllIssuedBefore(any(), any())).thenReturn(Mono.error(new IllegalStateException("no Redis")));

        assertThatThrownBy(() -> service.resetPassword(teacher, ADMIN).block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("TOKEN_REVOCATION_UNAVAILABLE"));
        assertThatThrownBy(() -> service.update(teacher, InstructorDtos.UpdateRequest.builder().active(false).build(), true, ADMIN).block())
                .satisfies(e -> assertThat(code(e)).isEqualTo("TOKEN_REVOCATION_UNAVAILABLE"));
        assertThat(users.findById(teacher).block().getPasswordHash()).isEqualTo(hashBefore);
        assertThat(users.findById(teacher).block().isActive()).isTrue();
    }

    @Test
    void a_reset_gives_a_new_temporary_password_and_ends_the_sessions() {
        InstructorDtos.Created created = service.create(request("forgot@x.test"), ADMIN).block();
        db.sql("UPDATE users SET must_change_password = false, temp_password_expires_at = NULL WHERE id = :id")
                .bind("id", created.getId()).then().block();

        InstructorDtos.Created reset = service.resetPassword(created.getId(), ADMIN).block();
        assertThat(reset.getTemporaryPassword()).isNotEqualTo(created.getTemporaryPassword()).hasSize(16);
        var stored = users.findById(created.getId()).block();
        assertThat(stored.isMustChangePassword()).isTrue();
        assertThat(stored.getTempPasswordExpiresAt()).isAfter(LocalDateTime.now().plusDays(6));
        assertThat(encoder.matches(reset.getTemporaryPassword(), stored.getPasswordHash())).isTrue();
        assertThat(encoder.matches(created.getTemporaryPassword(), stored.getPasswordHash())).isFalse();
        verify(revocations).revokeAllIssuedBefore(eq(created.getId()), any());
        verify(tokenService).revokeAllUserTokens(created.getId());
        assertThat(scalar("SELECT count(*) FROM admin_audit_log WHERE action = 'INSTRUCTOR_PASSWORD_RESET' AND details IS NULL"
                + " AND target_user_id = '" + created.getId() + "'")).isEqualTo(1);
        passwordIsNowhere(reset.getTemporaryPassword());
    }

    @Test
    void another_account_than_a_teacher_is_404() {
        UUID student = db.sql("INSERT INTO users (email, password_hash, first_name, last_name, role) "
                        + "VALUES ('student-b13@x.test', 'x', 'Ali', 'Veli', 'STUDENT') RETURNING id")
                .map(row -> row.get(0, UUID.class)).one().block();
        for (UUID id : List.of(student, UUID.randomUUID())) {
            assertThatThrownBy(() -> service.resetPassword(id, ADMIN).block())
                    .satisfies(e -> assertThat(code(e)).isEqualTo("INSTRUCTOR_NOT_FOUND"));
            assertThatThrownBy(() -> service.update(id, InstructorDtos.UpdateRequest.builder().firstName("Xx").build(), true, ADMIN).block())
                    .satisfies(e -> assertThat(code(e)).isEqualTo("INSTRUCTOR_NOT_FOUND"));
        }
        assertThat(scalar("SELECT count(*) FROM users WHERE id = '" + student + "' AND role = 'STUDENT' AND password_hash = 'x'")).isEqualTo(1);
    }
}
