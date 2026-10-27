package com.examportal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import com.examportal.guest.GuestAuthService;
import com.examportal.guest.GuestOtpService;

/**
 * The whole auth-service starts: every bean is built and every migration runs, on a PostgreSQL 16 and a Redis of its
 * own (Testcontainers), with secrets made for this run. Catches what tests that build their classes by hand cannot,
 * such as a bean Spring cannot construct (2026-10-27: GuestOtpService). Skipped when no Docker is available.
 */
// The levels Spring Boot sets stay for the rest of the test JVM: without these, the SQL DEBUG lines of application.yml
// reach the log checks of later tests (GuestSignInFlowTest logs its own query with a code). They must be set here,
// not in @DynamicPropertySource, which comes after the logging system is configured.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "logging.level.org.springframework.r2dbc=INFO",
        "logging.level.org.springframework.security=INFO",
})
class AuthServiceContextTest {

    private static final boolean DOCKER = DockerClientFactory.instance().isDockerAvailable();
    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> redis;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        assumeTrue(DOCKER, "Docker is not available");
        postgres = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("exam_auth");
        postgres.start();
        redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        redis.start();

        String r2dbc = "r2dbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/exam_auth";
        registry.add("spring.r2dbc.url", () -> r2dbc);
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

        // Secrets of this run only
        registry.add("security.jwt.secret-key", () -> randomBase64(64));
        registry.add("auth.otp.hmac-secret", () -> randomBase64(32));
        registry.add("internal.service-secret", () -> randomBase64(32));
        // The local Mailpit settings: nothing is sent, nothing is required
        registry.add("spring.mail.host", () -> "mailpit");
        registry.add("spring.mail.port", () -> 1025);
        registry.add("spring.mail.properties.mail.smtp.auth", () -> false);
        registry.add("spring.mail.properties.mail.smtp.starttls.enable", () -> false);
        registry.add("spring.mail.properties.mail.smtp.starttls.required", () -> false);
        // No housekeeping run against the fresh database
        registry.add("auth.cleanup.run-on-startup", () -> false);
    }

    @AfterAll
    static void stop() {
        if (redis != null) redis.stop();
        if (postgres != null) postgres.stop();
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context.getBean(GuestOtpService.class)).isNotNull();
        assertThat(context.getBean(GuestAuthService.class)).isNotNull();
    }

    private static String randomBase64(int bytes) {
        byte[] value = new byte[bytes];
        new SecureRandom().nextBytes(value);
        return Base64.getEncoder().encodeToString(value);
    }
}
