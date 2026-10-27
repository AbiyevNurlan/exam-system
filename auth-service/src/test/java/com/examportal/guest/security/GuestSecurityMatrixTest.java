package com.examportal.guest.security;

import com.examportal.auth.config.SecurityConfig;
import com.examportal.common.security.JwtAuthenticationFilter;
import com.examportal.common.security.JwtTokenProvider;
import com.examportal.common.security.TokenRevocationStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.result.method.RequestMappingInfo;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * B13a item 4 and 8, the GUEST security matrix of auth-service, without any mock in the security path: both real
 * chains in their real order (GuestSecurityConfig, then the unchanged SecurityConfig), the real JwtAuthenticationFilter
 * and GuestAuthenticationFilter, real tokens of every role (ANONYMOUS = no token). The endpoints are read the way the
 * application reads them (every @RestController of com.examportal.auth and com.examportal.guest).
 *
 * - On every endpoint of the accounts (com.examportal.auth) a guest token gets exactly what no token gets: through
 *   only where anybody gets through (PUBLIC sign-in steps, INTERNAL /internal/** with its shared secret in the
 *   controller), 401/403 everywhere else.
 * - The guest endpoints let through exactly the callers of guest-endpoint-access.txt: account tokens of any role are
 *   refused on /api/guest/**, a guest token on /api/admin/guests/**.
 */
class GuestSecurityMatrixTest {

    enum Caller { ANONYMOUS, STUDENT, INSTRUCTOR, ADMIN, GUEST }

    enum Access {
        GUEST_ONLY(EnumSet.of(Caller.GUEST)),
        PUBLIC(EnumSet.allOf(Caller.class)),
        ADMIN_ONLY(EnumSet.of(Caller.ADMIN));

        final Set<Caller> callers;

        Access(Set<Caller> callers) {
            this.callers = callers;
        }
    }

    record Endpoint(String key, Method method) {
    }

    record Outcome(boolean reached, int status) {
    }

    static final String SECRET = GuestTokenSeparationTest.SECRET;
    private static final Map<Caller, String> TOKENS = new EnumMap<>(Caller.class);
    private static Map<String, Endpoint> accountEndpoints;
    private static Map<String, Endpoint> guestEndpoints;
    private static WebFilterChainProxy security;

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

    static Map<String, Endpoint> endpointsOf(String basePackage) throws ClassNotFoundException {
        Map<String, Endpoint> found = new TreeMap<>();
        Mappings mappings = new Mappings();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (var candidate : scanner.findCandidateComponents(basePackage)) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            mappings.of(controller).forEach((method, info) -> {
                for (RequestMethod verb : info.getMethodsCondition().getMethods()) {
                    for (PathPattern pattern : info.getPatternsCondition().getPatterns()) {
                        String key = verb.name() + " " + withoutRegex(pattern.getPatternString());
                        found.put(key, new Endpoint(key, method));
                    }
                }
            });
        }
        return found;
    }

    @BeforeAll
    static void load() throws Exception {
        accountEndpoints = endpointsOf("com.examportal.auth");
        guestEndpoints = endpointsOf("com.examportal.guest");

        JwtTokenProvider accounts = new JwtTokenProvider();
        ReflectionTestUtils.setField(accounts, "secretKey", SECRET);
        ReflectionTestUtils.setField(accounts, "accessTokenExpiration", 900_000L);
        ReflectionTestUtils.setField(accounts, "issuer", "exam-system");
        GuestTokenProvider guests = new GuestTokenProvider(SECRET, 30);
        for (Caller role : List.of(Caller.STUDENT, Caller.INSTRUCTOR, Caller.ADMIN)) {
            TOKENS.put(role, accounts.generateAccessToken(UUID.randomUUID(), role.name().toLowerCase() + "@x.test", role.name()));
        }
        TOKENS.put(Caller.GUEST, guests.issueAccessToken(UUID.randomUUID(), 4_141_592));

        @SuppressWarnings("unchecked")
        ObjectProvider<TokenRevocationStore> noRevocations = mock(ObjectProvider.class);
        JwtAuthenticationFilter jwt = new JwtAuthenticationFilter(accounts, noRevocations);
        security = new WebFilterChainProxy(
                GuestSecurityConfig.chain(ServerHttpSecurity.http(), jwt, new GuestAuthenticationFilter(guests)),
                new SecurityConfig(jwt).securityWebFilterChain(ServerHttpSecurity.http()));
    }

    // {userId:[0-9a-f]{8}-...} -> {userId}: a path variable without its regex (as EndpointRegistryTest)
    static String withoutRegex(String pattern) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        boolean skipping = false;
        for (char c : pattern.toCharArray()) {
            if (c == '{') {
                depth++;
                if (depth == 1 || !skipping) {
                    out.append(c);
                }
                continue;
            }
            if (c == '}') {
                depth--;
                if (depth == 0) {
                    skipping = false;
                    out.append(c);
                } else if (!skipping) {
                    out.append(c);
                }
                continue;
            }
            if (c == ':' && depth == 1) {
                skipping = true;
                continue;
            }
            if (!skipping) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static final Pattern VARIABLE = Pattern.compile("\\{([^}]+)}");

    static String sample(String path) {
        Matcher matcher = VARIABLE.matcher(path);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            matcher.appendReplacement(out, name.equals("userId") || name.equals("instructorId") || name.equals("id")
                    || name.equals("sessionId") ? UUID.randomUUID().toString() : "1");
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static Outcome call(String key, Caller caller) {
        String verb = key.substring(0, key.indexOf(' '));
        String path = sample(key.substring(key.indexOf(' ') + 1));
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.method(HttpMethod.valueOf(verb), path);
        if (TOKENS.containsKey(caller)) {
            request.header("Authorization", "Bearer " + TOKENS.get(caller));
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

    private static final Pattern ROLES = Pattern.compile("'([A-Z_]+)'");

    // The callers @PreAuthorize of the method (or else of its class) lets in; without one, everybody
    static Set<Caller> annotationLetsThrough(Method method) {
        PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        if (rule == null) {
            rule = AnnotatedElementUtils.findMergedAnnotation(method.getDeclaringClass(), PreAuthorize.class);
        }
        if (rule == null) {
            return EnumSet.allOf(Caller.class);
        }
        String expression = rule.value().replace(" ", "");
        if (expression.equals("isAuthenticated()")) {
            return EnumSet.of(Caller.STUDENT, Caller.INSTRUCTOR, Caller.ADMIN, Caller.GUEST);
        }
        assertThat(expression).matches("has(Any)?Role\\(('[A-Z_]+',?)+\\)");
        Set<Caller> callers = EnumSet.noneOf(Caller.class);
        Matcher matcher = ROLES.matcher(expression);
        while (matcher.find()) {
            callers.add(Caller.valueOf(matcher.group(1)));
        }
        return callers;
    }

    @Test
    void theEndpointsWereRead() {
        // 31 account endpoints at B13, 11 guest endpoints at B13a, 12 with the guest's events of B13b
        assertThat(accountEndpoints.size()).isGreaterThanOrEqualTo(28);
        assertThat(guestEndpoints).hasSize(12);
        assertThat(accountEndpoints.keySet()).doesNotContainAnyElementsOf(guestEndpoints.keySet());
    }

    @Test
    void onEveryAccountEndpointAGuestTokenIsNoToken() {
        List<String> different = new ArrayList<>();
        List<String> guestThrough = new ArrayList<>();
        accountEndpoints.keySet().forEach(key -> {
            Outcome anonymous = call(key, Caller.ANONYMOUS);
            Outcome guest = call(key, Caller.GUEST);
            if (anonymous.reached() != guest.reached() || anonymous.status() != guest.status()) {
                different.add(key + ": no token " + anonymous + ", guest token " + guest);
            }
            if (guest.reached()) {
                guestThrough.add(key);
            } else {
                assertThat(guest.status()).as(key).isIn(401, 403);
            }
        });
        assertThat(different).isEmpty();
        // Through only where no token is needed at all: the accounts' public steps and /internal/** (shared secret)
        assertThat(guestThrough).allSatisfy(key -> assertThat(key.contains(" /internal/") || List.of(
                "POST /auth/register", "POST /auth/verify-otp", "POST /auth/resend-otp", "POST /auth/login", "POST /auth/refresh",
                "POST /auth/forgot-password", "POST /auth/reset-password", "GET /auth/health").contains(key)).as(key).isTrue());
        // And every protected one of them was really tried: me, profile, password, the admin's, the teachers'
        assertThat(accountEndpoints.keySet()).contains("GET /auth/me", "PUT /auth/profile", "POST /auth/change-password",
                "GET /auth/admin/users", "GET /api/admin/instructors");
    }

    @Test
    void theGuestEndpointsLetThroughExactlyTheirCallers() throws Exception {
        Map<String, Access> registry = new TreeMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                GuestSecurityMatrixTest.class.getResourceAsStream("/guest-endpoint-access.txt")), StandardCharsets.UTF_8))) {
            for (String line : reader.lines().toList()) {
                String text = line.strip();
                if (text.isEmpty() || text.startsWith("#")) {
                    continue;
                }
                String[] parts = text.split("\\s+");
                assertThat(parts).hasSize(3);
                assertThat(registry.put(parts[0] + " " + parts[1], Access.valueOf(parts[2]))).as("listed twice: " + text).isNull();
            }
        }
        assertThat(registry.keySet()).as("guest endpoints and lines of guest-endpoint-access.txt").isEqualTo(guestEndpoints.keySet());

        List<String> wrong = new ArrayList<>();
        guestEndpoints.forEach((key, endpoint) -> {
            Set<Caller> annotation = annotationLetsThrough(endpoint.method());
            Set<Caller> through = EnumSet.noneOf(Caller.class);
            for (Caller caller : Caller.values()) {
                Outcome outcome = call(key, caller);
                if (outcome.reached() && annotation.contains(caller)) {
                    through.add(caller);
                } else if (!outcome.reached()) {
                    assertThat(outcome.status()).as(key + " " + caller).isIn(401, 403);
                }
            }
            Access access = registry.get(key);
            if (!through.equals(access.callers)) {
                wrong.add(key + " is " + access + " " + access.callers + " but lets through " + through);
            }
        });
        assertThat(wrong).isEmpty();
    }

    @Test
    void accountTokensAreForbiddenOnTheGuestsOwnPaths() {
        for (Caller role : List.of(Caller.STUDENT, Caller.INSTRUCTOR, Caller.ADMIN)) {
            assertThat(call("GET /api/guest/me", role)).isEqualTo(new Outcome(false, 403));
            assertThat(call("PATCH /api/guest/me", role)).isEqualTo(new Outcome(false, 403));
            // A path under /api/guest/ that does not exist yet is refused too, never open by accident
            assertThat(call("GET /api/guest/anything-later", role).reached()).isFalse();
        }
        assertThat(call("GET /api/guest/me", Caller.ANONYMOUS)).isEqualTo(new Outcome(false, 401));
        assertThat(call("GET /api/guest/me", Caller.GUEST).reached()).isTrue();
        assertThat(call("GET /api/admin/guests", Caller.GUEST)).isEqualTo(new Outcome(false, 401));
    }
}
