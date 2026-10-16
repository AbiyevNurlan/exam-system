package com.examportal.exam.client;

import com.examportal.common.domain.dto.profile.ParticipantProfileDtos;
import com.examportal.common.exception.ResourceNotFoundException;
import com.examportal.common.exception.ValidationException;
import com.examportal.common.security.InternalServiceAuth;
import com.examportal.exam.exception.GroupException;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B11: exam-service relays the participant profile to auth-service and passes its refusals on as the
 * same errors (400 with the field, 404), never anything else as a success; an outage is 503.
 * A small HTTP server stands in for auth-service.
 */
class UserDirectoryProfileTest {

    private static final String SECRET = "s".repeat(40);
    private DisposableServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.disposeNow();
        }
    }

    private UserDirectoryClient client(int status, String body, AtomicReference<String> seen) {
        server = HttpServer.create().port(0)
                .handle((request, response) -> {
                    seen.set(request.method() + " " + request.uri() + " secret=" + SECRET.equals(request.requestHeaders().get(InternalServiceAuth.HEADER)));
                    return request.receive().aggregate().asString().defaultIfEmpty("")
                            .flatMap(sent -> {
                                seen.set(seen.get() + " body=" + sent);
                                return response.status(HttpResponseStatus.valueOf(status))
                                        .header("Content-Type", "application/json").sendString(Mono.just(body)).then();
                            });
                })
                .bindNow();
        return new UserDirectoryClient(WebClient.builder(), "http://localhost:" + server.port(), SECRET);
    }

    @Test
    void readsTheProfileWithTheSecret() {
        AtomicReference<String> seen = new AtomicReference<>();
        UUID user = UUID.randomUUID();
        UserDirectoryClient directory = client(200, """
                {"success":true,"data":{"profile":null,"categories":["STUDENT","INTERN"],"requiredFields":{}}}""", seen);

        StepVerifier.create(directory.profile(user))
                .assertNext(mine -> {
                    assertThat(mine.getProfile()).isNull();
                    assertThat(mine.getCategories()).containsExactly("STUDENT", "INTERN");
                })
                .verifyComplete();
        assertThat(seen.get()).startsWith("GET /internal/users/" + user + "/profile secret=true");
    }

    @Test
    void savesWithTheActorAndPassesARefusedFieldOn() {
        AtomicReference<String> seen = new AtomicReference<>();
        UUID user = UUID.randomUUID();
        UserDirectoryClient directory = client(400, """
                {"success":false,"message":"Validation failed for field 'city': must be at most 100 characters",
                 "error":{"code":"VALIDATION_ERROR","description":"Validation failed for field 'city': must be at most 100 characters"}}""", seen);

        StepVerifier.create(directory.saveProfile(user, user, ParticipantProfileDtos.UpdateRequest.builder().category("OTHER").build()))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ValidationException.class).hasMessageContaining("city"))
                .verify();
        assertThat(seen.get()).startsWith("PUT /internal/users/" + user + "/profile secret=true")
                .contains("\"actorId\":\"" + user + "\"").contains("\"category\":\"OTHER\"");
    }

    @Test
    void anUnknownAccountIs404AndAnOutageIs503() {
        AtomicReference<String> seen = new AtomicReference<>();
        StepVerifier.create(client(404, """
                        {"success":false,"error":{"code":"RESOURCE_NOT_FOUND","description":"User not found"}}""", seen)
                        .profile(UUID.randomUUID()))
                .expectError(ResourceNotFoundException.class).verify();
        server.disposeNow();

        StepVerifier.create(client(500, "{}", seen).profile(UUID.randomUUID()))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(GroupException.class).hasMessageContaining("unavailable"))
                .verify();
    }

    @Test
    void withoutASecretNothingIsSent() {
        UserDirectoryClient unconfigured = new UserDirectoryClient(WebClient.builder(), "http://localhost:1", "");
        StepVerifier.create(unconfigured.profile(UUID.randomUUID())).expectError(GroupException.class).verify();
    }
}
