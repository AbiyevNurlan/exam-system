package com.examportal.paid;

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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B13b item 14: PAID_ACCESS_ENABLED off (the default) - every endpoint of paid open exams answers 404 FEATURE_DISABLED
 * before anything else, and every endpoint of the exams of today (endpoint-access.txt) passes untouched, whether the
 * switch is on or off.
 */
class PaidFeatureFilterTest {

    record Answer(boolean passed, int status, String body) {
    }

    private static List<String> lines(String resource) throws Exception {
        List<String> keys = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                PaidFeatureFilterTest.class.getResourceAsStream(resource)), StandardCharsets.UTF_8))) {
            for (String line : reader.lines().toList()) {
                String text = line.strip();
                if (!text.isEmpty() && !text.startsWith("#")) {
                    String[] parts = text.split("\\s+");
                    keys.add(parts[0] + " " + parts[1].replaceAll("\\{[^}]+}", "1"));
                }
            }
        }
        return keys;
    }

    private static Answer call(boolean enabled, String key) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.method(
                HttpMethod.valueOf(key.substring(0, key.indexOf(' '))), key.substring(key.indexOf(' ') + 1)));
        AtomicBoolean passed = new AtomicBoolean();
        new PaidFeatureFilter(new PaidFeature(enabled)).filter(exchange, through -> {
            passed.set(true);
            return Mono.empty();
        }).block();
        var status = exchange.getResponse().getStatusCode();
        return new Answer(passed.get(), status == null ? 200 : status.value(), passed.get() ? null : exchange.getResponse().getBodyAsString().block());
    }

    @Test
    void offEveryPaidEndpointIs404AndTheExamsOfTodayPass() throws Exception {
        List<String> paid = lines("/paid-endpoint-access.txt");
        assertThat(paid).hasSizeGreaterThan(30);
        for (String key : paid) {
            Answer off = call(false, key);
            assertThat(off.passed()).as(key).isFalse();
            assertThat(off.status()).as(key).isEqualTo(404);
            assertThat(off.body()).as(key).contains("\"code\":\"FEATURE_DISABLED\"");
            assertThat(call(true, key).passed()).as(key).isTrue();
        }
        List<String> today = lines("/endpoint-access.txt");
        assertThat(today).hasSizeGreaterThan(80);
        for (String key : today) {
            assertThat(call(false, key).passed()).as(key).isTrue();
            assertThat(call(true, key).passed()).as(key).isTrue();
        }
        // Paths that only start like paid ones are not
        assertThat(call(false, "GET /api/admin/ordersheet").passed()).isTrue();
        assertThat(call(false, "GET /api/publicity").passed()).isTrue();
    }
}
