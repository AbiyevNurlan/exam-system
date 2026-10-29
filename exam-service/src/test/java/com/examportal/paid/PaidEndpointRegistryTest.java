package com.examportal.paid;

import com.examportal.common.security.GuestTokens;
import com.examportal.common.security.JwtAuthenticationFilter;
import com.examportal.common.security.JwtTokenProvider;
import com.examportal.common.security.TokenRevocationStore;
import com.examportal.exam.config.SecurityConfig;
import com.examportal.paid.security.PaidGuestAuthenticationFilter;
import com.examportal.paid.security.PaidSecurityConfig;
import io.jsonwebtoken.Jwts;
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
import java.util.Base64;
import java.util.Date;
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
 * B13b item 14, the access matrix of paid open exams without a mock in the security path: both real chains of
 * exam-service in their real order (PaidSecurityConfig, then the unchanged SecurityConfig), the real
 * JwtAuthenticationFilter and PaidGuestAuthenticationFilter, real tokens (ANONYMOUS = none, STUDENT, INSTRUCTOR, ADMIN,
 * GUEST). Every endpoint of com.examportal.paid lets through exactly the callers of paid-endpoint-access.txt; and on
 * every endpoint of the exams of today (com.examportal.exam) a guest token is still exactly no token (B13a).
 */
class PaidEndpointRegistryTest {

    enum Caller { ANONYMOUS, STUDENT, INSTRUCTOR, ADMIN, GUEST }

    enum Access {
        PUBLIC(EnumSet.allOf(Caller.class)),
        GUEST_ONLY(EnumSet.of(Caller.GUEST)),
        ADMIN_ONLY(EnumSet.of(Caller.ADMIN));

        final Set<Caller> callers;

        Access(Set<Caller> callers) {
            this.callers = callers;
        }
    }

    record Outcome(boolean reached, int status) {
    }

    static final String SECRET = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef-test-secret".getBytes());
    private static final Map<Caller, String> TOKENS = new EnumMap<>(Caller.class);
    private static Map<String, Method> paid;
    private static Map<String, Method> exams;
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

    static Map<String, Method> endpointsOf(String basePackage) throws ClassNotFoundException {
        Map<String, Method> found = new TreeMap<>();
        Mappings mappings = new Mappings();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (var candidate : scanner.findCandidateComponents(basePackage)) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            mappings.of(controller).forEach((method, info) -> {
                for (RequestMethod verb : info.getMethodsCondition().getMethods()) {
                    for (PathPattern pattern : info.getPatternsCondition().getPatterns()) {
                        found.put(verb.name() + " " + com.examportal.exam.config.PaidAccessHelper.withoutRegex(pattern.getPatternString()), method);
                    }
                }
            });
        }
        return found;
    }

    @BeforeAll
    static void load() throws Exception {
        paid = endpointsOf("com.examportal.paid");
        exams = endpointsOf("com.examportal.exam");

        JwtTokenProvider accounts = new JwtTokenProvider();
        ReflectionTestUtils.setField(accounts, "secretKey", SECRET);
        ReflectionTestUtils.setField(accounts, "accessTokenExpiration", 900_000L);
        ReflectionTestUtils.setField(accounts, "issuer", "exam-system");
        for (Caller role : List.of(Caller.STUDENT, Caller.INSTRUCTOR, Caller.ADMIN)) {
            TOKENS.put(role, accounts.generateAccessToken(UUID.randomUUID(), role.name().toLowerCase() + "@x.test", role.name()));
        }
        Date now = new Date();
        TOKENS.put(Caller.GUEST, Jwts.builder().subject(UUID.randomUUID().toString()).issuer(GuestTokens.ISSUER).issuedAt(now)
                .expiration(new Date(now.getTime() + 1_800_000)).claim("role", GuestTokens.ROLE).claim("type", GuestTokens.ACCESS_TYPE)
                .claim(GuestTokens.CODE_CLAIM, 4_141_592).signWith(GuestTokens.signingKey(SECRET)).compact());

        @SuppressWarnings("unchecked")
        ObjectProvider<TokenRevocationStore> none = mock(ObjectProvider.class);
        JwtAuthenticationFilter jwt = new JwtAuthenticationFilter(accounts, none);
        security = new WebFilterChainProxy(
                PaidSecurityConfig.chain(ServerHttpSecurity.http(), jwt, new PaidGuestAuthenticationFilter(GuestTokens.signingKey(SECRET))),
                new SecurityConfig(jwt).securityWebFilterChain(ServerHttpSecurity.http()));
    }

    private static final Pattern VARIABLE = Pattern.compile("\\{([^}]+)}");

    static String sample(String path) {
        Matcher matcher = VARIABLE.matcher(path);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = switch (name) {
                case "userId", "mediaId", "guestId" -> UUID.randomUUID().toString();
                case "provider" -> "sandbox";
                case "orderNo" -> "YK-261023-000001";
                case "sessionId" -> UUID.randomUUID().toString();
                default -> "1";
            };
            matcher.appendReplacement(out, value);
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static Outcome call(String key, Caller caller) {
        String verb = key.substring(0, key.indexOf(' '));
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.method(HttpMethod.valueOf(verb), sample(key.substring(key.indexOf(' ') + 1)));
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

    static Set<Caller> annotationLetsThrough(Method method) {
        PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        if (rule == null) {
            rule = AnnotatedElementUtils.findMergedAnnotation(method.getDeclaringClass(), PreAuthorize.class);
        }
        if (rule == null) {
            return EnumSet.allOf(Caller.class);
        }
        String expression = rule.value().replace(" ", "");
        assertThat(expression).matches("has(Any)?Role\\(('[A-Z_]+',?)+\\)");
        Set<Caller> callers = EnumSet.noneOf(Caller.class);
        Matcher matcher = ROLES.matcher(expression);
        while (matcher.find()) {
            callers.add(Caller.valueOf(matcher.group(1)));
        }
        return callers;
    }

    static final String DEV_ONLY = "DEV_ONLY";

    /** The lines of paid-endpoint-access.txt: endpoint, its class, and whether it is DEV_ONLY (4th column) */
    static Map<String, String[]> lines() throws Exception {
        Map<String, String[]> lines = new TreeMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                PaidEndpointRegistryTest.class.getResourceAsStream("/paid-endpoint-access.txt")), StandardCharsets.UTF_8))) {
            for (String line : reader.lines().toList()) {
                String text = line.strip();
                if (text.isEmpty() || text.startsWith("#")) {
                    continue;
                }
                String[] parts = text.split("\\s+");
                assertThat(parts.length).as(text).isBetween(3, 4);
                if (parts.length == 4) {
                    assertThat(parts[3]).as(text).isEqualTo(DEV_ONLY);
                }
                assertThat(lines.put(parts[0] + " " + parts[1], parts)).as("listed twice: " + text).isNull();
            }
        }
        return lines;
    }

    @Test
    void everyPaidEndpointLetsThroughExactlyItsCallers() throws Exception {
        Map<String, Access> registry = new TreeMap<>();
        lines().forEach((key, parts) -> registry.put(key, Access.valueOf(parts[2])));
        assertThat(registry.keySet()).as("paid endpoints and lines of paid-endpoint-access.txt").isEqualTo(paid.keySet());

        List<String> wrong = new ArrayList<>();
        paid.forEach((key, method) -> {
            Set<Caller> annotation = annotationLetsThrough(method);
            Set<Caller> through = EnumSet.noneOf(Caller.class);
            for (Caller caller : Caller.values()) {
                Outcome outcome = call(key, caller);
                if (outcome.reached() && annotation.contains(caller)) {
                    through.add(caller);
                } else if (!outcome.reached()) {
                    assertThat(outcome.status()).as(key + " " + caller).isIn(401, 403);
                }
            }
            if (!through.equals(registry.get(key).callers)) {
                wrong.add(key + " is " + registry.get(key) + " but lets through " + through);
            }
        });
        assertThat(wrong).isEmpty();
    }

    /**
     * Production: exactly the sandbox's own endpoints are DEV_ONLY, and each answers 404 under the production profile even
     * with a valid signed link, as does the sandbox's notification; the security chains above are the same in every
     * profile (the guard is behind them, in the sandbox provider)
     */
    @Test
    void inProductionTheDevOnlyEndpointsDoNotExist() throws Exception {
        Set<String> devOnly = new java.util.TreeSet<>();
        lines().forEach((key, parts) -> {
            if (parts.length == 4) {
                devOnly.add(key);
            }
        });
        Set<String> sandboxEndpoints = new java.util.TreeSet<>();
        paid.forEach((key, method) -> {
            if (method.getDeclaringClass() == com.examportal.paid.web.SandboxCheckoutController.class) {
                sandboxEndpoints.add(key);
            }
        });
        assertThat(devOnly).as("DEV_ONLY lines and the endpoints of SandboxCheckoutController").isEqualTo(sandboxEndpoints).isNotEmpty();

        var guard = new com.examportal.paid.provider.SandboxProductionGuard(true, true, "SANDBOX", true, "");
        var sandbox = new com.examportal.paid.provider.SandboxPaymentProvider(true, "secret-secret-secret", "", guard);
        var orders = mock(com.examportal.paid.order.OrderRepository.class);
        org.mockito.Mockito.when(orders.findByNo(org.mockito.ArgumentMatchers.anyString())).thenReturn(Mono.empty());
        var web = org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                new com.examportal.paid.web.SandboxCheckoutController(sandbox, orders,
                        mock(com.examportal.paid.payment.PaymentWebhookService.class))).build();
        for (String key : devOnly) {
            String path = sample(key.substring(key.indexOf(' ') + 1));
            String uri = path + "?sig=" + sandbox.linkSignature(path.replaceAll("^/api/payments/sandbox/checkout/([^/]+).*$", "$1"));
            web.method(HttpMethod.valueOf(key.substring(0, key.indexOf(' ')))).uri(uri).exchange().expectStatus().isNotFound();
        }
        // The sandbox's notification: 404 before any signature check or event row
        assertThat(sandbox.hidden()).isTrue();
        assertThat(sample("/api/payments/webhook/{provider}")).isEqualTo("/api/payments/webhook/sandbox");

        // The payment DEMO (PAYMENTS_DEMO_MODE with PAYMENTS_PROVIDER=SANDBOX and the sandbox switch): the same endpoints answer
        var demo = new com.examportal.paid.provider.SandboxPaymentProvider(true, "secret-secret-secret", "",
                new com.examportal.paid.provider.SandboxProductionGuard(true, true, "SANDBOX", true, null, true));
        assertThat(demo.hidden()).isFalse();
        var known = mock(com.examportal.paid.order.OrderRepository.class);
        org.mockito.Mockito.when(known.findByNo(org.mockito.ArgumentMatchers.anyString())).thenReturn(Mono.just(new com.examportal.paid.order.Order(
                1L, "YK-261023-000001", UUID.randomUUID(), 1_234_567, 7L, 2500, "AZN", com.examportal.paid.order.OrderStatus.PAID, "SANDBOX", "sbx_1",
                true, null, null, java.time.LocalDateTime.now(), java.time.LocalDateTime.now(), java.time.LocalDateTime.now(), null, null, null)));
        var demoWeb = org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                new com.examportal.paid.web.SandboxCheckoutController(demo, known, mock(com.examportal.paid.payment.PaymentWebhookService.class))).build();
        String page = sample("/api/payments/sandbox/checkout/{orderNo}");
        demoWeb.get().uri(page + "?sig=" + demo.linkSignature("YK-261023-000001")).exchange().expectStatus().isOk();
    }

    @Test
    void onTheExamsOfTodayAGuestTokenIsStillNoToken() {
        assertThat(exams.size()).isGreaterThan(80);
        List<String> different = new ArrayList<>();
        exams.keySet().forEach(key -> {
            Outcome anonymous = call(key, Caller.ANONYMOUS);
            Outcome guest = call(key, Caller.GUEST);
            if (!anonymous.equals(guest)) {
                different.add(key + ": no token " + anonymous + ", guest token " + guest);
            }
        });
        assertThat(different).isEmpty();
        // And the paid paths do not open anything of today: /api/exams stays closed to a guest
        assertThat(call("POST /api/exams/{id}/start", Caller.GUEST).reached()).isFalse();
        assertThat(call("GET /api/exams/session/{sessionId}/result", Caller.GUEST).reached()).isFalse();
    }
}
