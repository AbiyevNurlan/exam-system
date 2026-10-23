package com.examportal.gateway.config;

import com.examportal.common.security.GuestTokens;
import com.examportal.common.security.JwtAuthenticationFilter;
import com.examportal.common.security.JwtTokenProvider;
import com.examportal.common.security.TokenRevocationStore;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * B13a item 4 and 8 at the gateway: every path of every route of application.yml (read from the file, so a new route
 * is covered without a change here), each with GET and POST, through the real chains in their real order
 * (GuestGatewaySecurityConfig, then the unchanged SecurityConfig) and the real JwtAuthenticationFilter. Outside the
 * guest paths a guest token is no token (monitoring and websocket, which have no security of their own, included);
 * the guest paths pass the gateway for everybody, auth-service decides there (GuestSecurityMatrixTest).
 */
class GuestTokenGatewayTest {

    static final String SECRET = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef-test-secret".getBytes());

    private static final List<String> paths = new ArrayList<>();
    private static WebFilterChainProxy security;
    private static String guestToken;
    private static String studentToken;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws Exception {
        try (InputStream in = GuestTokenGatewayTest.class.getResourceAsStream("/application.yml")) {
            Map<String, Object> yaml = new Yaml().load(in);
            Map<String, Object> gateway = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) yaml.get("spring")).get("cloud")).get("gateway");
            for (Map<String, Object> route : (List<Map<String, Object>>) gateway.get("routes")) {
                for (Object predicate : (List<Object>) route.get("predicates")) {
                    String text = String.valueOf(predicate);
                    if (text.startsWith("Path=")) {
                        for (String pattern : text.substring(5).split(",")) {
                            paths.add(pattern.trim().replace("/**", "/x/1").replace("*", "1"));
                        }
                    }
                }
            }
        }

        JwtTokenProvider accounts = new JwtTokenProvider();
        ReflectionTestUtils.setField(accounts, "secretKey", SECRET);
        ReflectionTestUtils.setField(accounts, "accessTokenExpiration", 900_000L);
        ReflectionTestUtils.setField(accounts, "issuer", "exam-system");
        studentToken = accounts.generateAccessToken(UUID.randomUUID(), "s@x.test", "STUDENT");
        Date now = new Date();
        guestToken = Jwts.builder().subject(UUID.randomUUID().toString()).issuer(GuestTokens.ISSUER).issuedAt(now)
                .expiration(new Date(now.getTime() + 1_800_000)).claim("role", GuestTokens.ROLE).claim("type", GuestTokens.ACCESS_TYPE)
                .claim(GuestTokens.CODE_CLAIM, 4_141_592).signWith(GuestTokens.signingKey(SECRET)).compact();

        ObjectProvider<TokenRevocationStore> noRevocations = mock(ObjectProvider.class);
        JwtAuthenticationFilter jwt = new JwtAuthenticationFilter(accounts, noRevocations);
        security = new WebFilterChainProxy(
                new GuestGatewaySecurityConfig().guestGatewaySecurityWebFilterChain(ServerHttpSecurity.http()),
                new SecurityConfig(jwt).securityWebFilterChain(ServerHttpSecurity.http()));
    }

    record Outcome(boolean reached, int status) {
    }

    static Outcome call(HttpMethod verb, String path, String token) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.method(verb, path);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicBoolean reached = new AtomicBoolean();
        security.filter(exchange, through -> {
            reached.set(true);
            return Mono.empty();
        }).block();
        var status = exchange.getResponse().getStatusCode();
        return new Outcome(reached.get(), status == null ? 200 : status.value());
    }

    static boolean guestPath(String path) {
        return path.startsWith("/auth/guest/") || path.startsWith("/api/guest/");
    }

    @Test
    void outsideTheGuestPathsAGuestTokenIsNoToken() {
        assertThat(paths).contains("/api/monitoring/x/1", "/ws/x/1", "/api/exams/x/1", "/auth/x/1", "/api/admin/guests");
        List<String> different = new ArrayList<>();
        for (String path : paths) {
            if (guestPath(path)) {
                continue;
            }
            for (HttpMethod verb : List.of(HttpMethod.GET, HttpMethod.POST)) {
                Outcome anonymous = call(verb, path, null);
                Outcome guest = call(verb, path, guestToken);
                if (!anonymous.equals(guest)) {
                    different.add(verb + " " + path + ": no token " + anonymous + ", guest token " + guest);
                }
                if (!guest.reached()) {
                    assertThat(guest.status()).as(verb + " " + path).isEqualTo(401);
                }
            }
        }
        assertThat(different).isEmpty();
        // The account token does get through, so the comparison above means something
        assertThat(call(HttpMethod.GET, "/api/exams/x/1", studentToken).reached()).isTrue();
        assertThat(call(HttpMethod.GET, "/api/exams/x/1", guestToken)).isEqualTo(new Outcome(false, 401));
        assertThat(call(HttpMethod.GET, "/api/monitoring/x/1", guestToken)).isEqualTo(new Outcome(false, 401));
        assertThat(call(HttpMethod.GET, "/api/admin/guests", guestToken)).isEqualTo(new Outcome(false, 401));
    }

    @Test
    void theGuestPathsPassTheGatewayForAuthServiceToDecide() {
        assertThat(paths).contains("/auth/guest/otp/request", "/auth/guest/x/1", "/api/guest/x/1");
        for (String token : new String[]{null, guestToken, studentToken}) {
            assertThat(call(HttpMethod.POST, "/auth/guest/otp/request", token).reached()).isTrue();
            assertThat(call(HttpMethod.GET, "/api/guest/me", token).reached()).isTrue();
        }
    }
}
