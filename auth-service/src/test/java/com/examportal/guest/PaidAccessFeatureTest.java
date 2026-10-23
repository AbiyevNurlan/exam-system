package com.examportal.guest;

import com.examportal.guest.web.GuestController;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * B13a: PAID_ACCESS_ENABLED. Off (the default): every guest endpoint of guest-endpoint-access.txt answers 404
 * FEATURE_DISABLED before anything else runs, except GET /api/guest/feature (200, false); every endpoint of the
 * accounts of endpoint-access.txt passes the filter untouched. On: everything passes.
 */
class PaidAccessFeatureTest {

    record Answer(boolean passed, int status, String body) {
    }

    private static List<String> lines(String resource) throws Exception {
        List<String> keys = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                PaidAccessFeatureTest.class.getResourceAsStream(resource)), StandardCharsets.UTF_8))) {
            for (String line : reader.lines().toList()) {
                String text = line.strip();
                if (!text.isEmpty() && !text.startsWith("#")) {
                    String[] parts = text.split("\\s+");
                    keys.add(parts[0] + " " + parts[1].replaceAll("\\{[^}]+}", UUID.randomUUID().toString()));
                }
            }
        }
        return keys;
    }

    private static Answer call(boolean enabled, String key) {
        String verb = key.substring(0, key.indexOf(' '));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.valueOf(verb),
                key.substring(key.indexOf(' ') + 1)));
        AtomicBoolean passed = new AtomicBoolean();
        new PaidAccessFeatureFilter(new GuestFeature(enabled)).filter(exchange, through -> {
            passed.set(true);
            return Mono.empty();
        }).block();
        var status = exchange.getResponse().getStatusCode();
        return new Answer(passed.get(), status == null ? 200 : status.value(),
                passed.get() ? null : exchange.getResponse().getBodyAsString().block());
    }

    @Test
    void offEveryGuestEndpointButTheSwitchIs404() throws Exception {
        List<String> guestEndpoints = lines("/guest-endpoint-access.txt");
        assertThat(guestEndpoints).hasSize(11);
        for (String key : guestEndpoints) {
            Answer answer = call(false, key);
            if (key.equals("GET /api/guest/feature")) {
                assertThat(answer.passed()).isTrue();
            } else {
                assertThat(answer.passed()).as(key).isFalse();
                assertThat(answer.status()).as(key).isEqualTo(404);
                assertThat(answer.body()).as(key).contains("\"code\":\"FEATURE_DISABLED\"");
            }
        }
        // Paths of the feature that do not exist yet answer the same
        assertThat(call(false, "GET /api/guest/exams").status()).isEqualTo(404);
        assertThat(call(false, "POST /auth/guest/anything").status()).isEqualTo(404);
    }

    @Test
    void theAccountsEndpointsPassWhetherOnOrOff() throws Exception {
        List<String> accountEndpoints = lines("/endpoint-access.txt");
        assertThat(accountEndpoints.size()).isGreaterThanOrEqualTo(28);
        for (String key : accountEndpoints) {
            assertThat(call(false, key).passed()).as(key).isTrue();
            assertThat(call(true, key).passed()).as(key).isTrue();
        }
        // A path that only starts like a guest path is not one
        assertThat(call(false, "GET /api/guestbook").passed()).isTrue();
        assertThat(call(false, "GET /auth/guests").passed()).isTrue();
    }

    @Test
    void onEverythingPasses() throws Exception {
        for (String key : lines("/guest-endpoint-access.txt")) {
            assertThat(call(true, key).passed()).as(key).isTrue();
        }
    }

    @Test
    void theSwitchTellsTheFlag() {
        GuestService unused = mock(GuestService.class);
        assertThat(new GuestController(unused, new GuestFeature(false)).feature().block().getBody().getData().paidAccessEnabled()).isFalse();
        assertThat(new GuestController(unused, new GuestFeature(true)).feature().block().getBody().getData().paidAccessEnabled()).isTrue();
    }
}
