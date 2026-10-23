package com.examportal.exam.config;

import com.examportal.common.security.GuestTokens;
import com.examportal.common.security.JwtAuthenticationFilter;
import com.examportal.common.security.JwtTokenProvider;
import com.examportal.common.security.TokenRevocationStore;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.result.method.RequestMappingInfo;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * B13a item 4 and 8 for exam-service: on every endpoint (read like EndpointRegistryTest, every @RestController) a guest
 * token (role GUEST, signed with the guest key of common GuestTokens) gets exactly what no token gets, through the
 * real SecurityConfig chain and the real JwtAuthenticationFilter: groups, exams, results, sessions, media, grading,
 * me/stats, me/profile, the admin's and the teachers' paths are all 401/403. A student token on the same chain does
 * get through somewhere, so the comparison is not empty.
 */
class GuestTokenMatrixTest {

    static final String SECRET = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef-test-secret".getBytes());

    private static final TreeSet<String> endpoints = new TreeSet<>();
    private static WebFilterChainProxy security;
    private static String guestToken;
    private static String studentToken;

    static final class Mappings extends RequestMappingHandlerMapping {
        Mappings() {
            GenericApplicationContext context = new GenericApplicationContext();
            context.refresh();
            setApplicationContext(context);
            afterPropertiesSet();
        }

        Map<Method, RequestMappingInfo> of(Class<?> controller) {
            return MethodIntrospector.selectMethods(controller,
                    (MethodIntrospector.MetadataLookup<RequestMappingInfo>) method -> getMappingForMethod(method, controller));
        }
    }

    @BeforeAll
    static void load() throws Exception {
        Mappings mappings = new Mappings();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (var candidate : scanner.findCandidateComponents("com.examportal.exam")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            mappings.of(controller).forEach((method, info) -> {
                for (RequestMethod verb : info.getMethodsCondition().getMethods()) {
                    for (PathPattern pattern : info.getPatternsCondition().getPatterns()) {
                        endpoints.add(verb.name() + " " + EndpointRegistryTest.withoutRegex(pattern.getPatternString()));
                    }
                }
            });
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

        @SuppressWarnings("unchecked")
        ObjectProvider<TokenRevocationStore> noRevocations = mock(ObjectProvider.class);
        security = new WebFilterChainProxy(new SecurityConfig(new JwtAuthenticationFilter(accounts, noRevocations))
                .securityWebFilterChain(ServerHttpSecurity.http()));
    }

    record Outcome(boolean reached, int status) {
    }

    static Outcome call(String key, String token) {
        String verb = key.substring(0, key.indexOf(' '));
        String path = EndpointRegistryTest.sample(key.substring(key.indexOf(' ') + 1));
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.method(HttpMethod.valueOf(verb), path);
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

    @Test
    void onEveryEndpointAGuestTokenIsNoToken() {
        assertThat(endpoints.size()).as("endpoints read").isGreaterThan(80);
        assertThat(endpoints).contains("GET /api/me/stats", "GET /api/exams/available", "GET /api/admin/groups",
                "GET /api/admin/participants");

        List<String> different = new ArrayList<>();
        List<String> guestThrough = new ArrayList<>();
        int studentThrough = 0;
        for (String key : endpoints) {
            Outcome anonymous = call(key, null);
            Outcome guest = call(key, guestToken);
            if (!anonymous.equals(guest)) {
                different.add(key + ": no token " + anonymous + ", guest token " + guest);
            }
            if (guest.reached()) {
                guestThrough.add(key);
            } else {
                assertThat(guest.status()).as(key).isIn(401, 403);
            }
            if (call(key, studentToken).reached()) {
                studentThrough++;
            }
        }
        assertThat(different).isEmpty();
        // Through only where no token is needed: /internal/** (shared secret in the controller) and what a join link is for
        assertThat(guestThrough).allSatisfy(key -> assertThat(key.contains(" /internal/") || key.equals("GET /api/join/{code}"))
                .as(key).isTrue());
        assertThat(studentThrough).isGreaterThan(guestThrough.size());
    }
}
