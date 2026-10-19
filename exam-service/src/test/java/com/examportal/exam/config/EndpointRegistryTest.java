package com.examportal.exam.config;

import com.examportal.common.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.result.method.RequestMappingInfo;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B13: every endpoint of exam-service has a class in endpoint-access.txt (ADMIN_ONLY, OWNER_SCOPED, OWN_DATA,
 * STUDENT_ONLY, PUBLIC, INTERNAL). The endpoints are read the way the application reads them
 * (RequestMappingHandlerMapping over every @RestController); who really gets through is asked from the real
 * security chain of SecurityConfig (path rules) together with @PreAuthorize of the method or its class, and must
 * be what the class says. A new endpoint without a line, a line without an endpoint, or a rule that lets another
 * role in fails the build.
 */
class EndpointRegistryTest {

    enum Caller { ANONYMOUS, STUDENT, INSTRUCTOR, ADMIN }

    enum Access {
        ADMIN_ONLY(EnumSet.of(Caller.ADMIN)),
        OWNER_SCOPED(EnumSet.of(Caller.ADMIN, Caller.INSTRUCTOR)),
        OWN_DATA(EnumSet.of(Caller.ADMIN, Caller.INSTRUCTOR, Caller.STUDENT)),
        STUDENT_ONLY(EnumSet.of(Caller.ADMIN, Caller.INSTRUCTOR, Caller.STUDENT)),
        PUBLIC(EnumSet.allOf(Caller.class)),
        // The shared secret is checked by the controller (404 without it), not by the security chain
        INTERNAL(EnumSet.allOf(Caller.class));

        final Set<Caller> callers;

        Access(Set<Caller> callers) {
            this.callers = callers;
        }
    }

    record Endpoint(String key, Method method) {
    }

    private static final String ROLE_HEADER = "X-Test-Role";
    private static Map<String, Endpoint> endpoints;
    private static Map<String, Access> registry;
    private static WebFilterChainProxy security;

    /** The mapping the application builds, without any bean: only the RequestMappingInfo of each method */
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
        endpoints = new TreeMap<>();
        Mappings mappings = new Mappings();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (var candidate : scanner.findCandidateComponents("com.examportal.exam")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            mappings.of(controller).forEach((method, info) -> {
                for (RequestMethod verb : info.getMethodsCondition().getMethods()) {
                    for (PathPattern pattern : info.getPatternsCondition().getPatterns()) {
                        String key = verb.name() + " " + withoutRegex(pattern.getPatternString());
                        assertThat(endpoints.put(key, new Endpoint(key, method))).as("mapped twice: " + key).isNull();
                    }
                }
                assertThat(info.getMethodsCondition().getMethods()).as("no HTTP method: " + method).isNotEmpty();
            });
        }

        registry = new TreeMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                EndpointRegistryTest.class.getResourceAsStream("/endpoint-access.txt")), StandardCharsets.UTF_8))) {
            for (String line : reader.lines().toList()) {
                String text = line.strip();
                if (text.isEmpty() || text.startsWith("#")) {
                    continue;
                }
                String[] parts = text.split("\\s+");
                assertThat(parts).as("method, path, class: " + text).hasSize(3);
                assertThat(registry.put(parts[0] + " " + parts[1], Access.valueOf(parts[2]))).as("listed twice: " + text).isNull();
            }
        }

        // The real chain of SecurityConfig; the JWT filter is replaced by one that signs in with the role of a header
        JwtAuthenticationFilter jwt = mock(JwtAuthenticationFilter.class);
        when(jwt.filter(any(), any())).thenAnswer(call -> {
            ServerWebExchange exchange = call.getArgument(0);
            WebFilterChain chain = call.getArgument(1);
            String role = exchange.getRequest().getHeaders().getFirst(ROLE_HEADER);
            if (role == null) {
                return chain.filter(exchange);
            }
            var authentication = new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role)));
            return chain.filter(exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
        });
        security = new WebFilterChainProxy(new SecurityConfig(jwt).securityWebFilterChain(ServerHttpSecurity.http()));
    }

    // {userId:[0-9a-f]{8}-...} -> {userId}; the braces inside the regex are counted
    static String withoutRegex(String pattern) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        boolean skipping = false;
        for (char c : pattern.toCharArray()) {
            if (c == '{') {
                depth++;
                if (depth == 1) {
                    out.append(c);
                } else if (!skipping) {
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

    // A concrete path: user ids are UUIDs (path rules match them by regex), everything else 1
    static String sample(String path) {
        Matcher matcher = VARIABLE.matcher(path);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            matcher.appendReplacement(out, name.equals("userId") || name.equals("instructorId") || name.equals("sessionId")
                    ? UUID.randomUUID().toString() : "1");
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean pathRuleLetsThrough(String verb, String path, Caller caller) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.method(HttpMethod.valueOf(verb), path);
        if (caller != Caller.ANONYMOUS) {
            request.header(ROLE_HEADER, caller.name());
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicBoolean reached = new AtomicBoolean();
        security.filter(exchange, through -> {
            reached.set(true);
            return Mono.empty();
        }).block();
        return reached.get();
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
            return EnumSet.of(Caller.STUDENT, Caller.INSTRUCTOR, Caller.ADMIN);
        }
        assertThat(expression).as("a @PreAuthorize this test does not understand: " + method)
                .matches("has(Any)?Role\\(('[A-Z_]+',?)+\\)");
        Set<Caller> callers = EnumSet.noneOf(Caller.class);
        Matcher matcher = ROLES.matcher(expression);
        while (matcher.find()) {
            callers.add(Caller.valueOf(matcher.group(1)));
        }
        return callers;
    }

    @Test
    void the_mapping_was_read() {
        // 87 at B13; far fewer means the scan or the mapping broke, not that endpoints were removed
        assertThat(endpoints.size()).isGreaterThanOrEqualTo(80);
    }

    @Test
    void every_endpoint_has_a_class_and_every_line_an_endpoint() {
        assertThat(new ArrayList<>(endpoints.keySet())).as("endpoints without a line in endpoint-access.txt")
                .isSubsetOf(registry.keySet());
        assertThat(new ArrayList<>(registry.keySet())).as("lines in endpoint-access.txt without an endpoint")
                .isSubsetOf(endpoints.keySet());
    }

    @Test
    void only_the_callers_of_the_class_get_through() {
        List<String> wrong = new ArrayList<>();
        endpoints.forEach((key, endpoint) -> {
            Access access = registry.get(key);
            if (access == null) {
                return;
            }
            String verb = key.substring(0, key.indexOf(' '));
            String path = sample(key.substring(key.indexOf(' ') + 1));
            Set<Caller> annotation = annotationLetsThrough(endpoint.method());
            Set<Caller> through = EnumSet.noneOf(Caller.class);
            for (Caller caller : Caller.values()) {
                if (annotation.contains(caller) && pathRuleLetsThrough(verb, path, caller)) {
                    through.add(caller);
                }
            }
            if (!through.equals(access.callers)) {
                wrong.add(key + " is " + access + " " + access.callers + " but lets through " + through);
            }
        });
        assertThat(wrong).isEmpty();
    }

    @Test
    void internal_endpoints_live_under_internal_and_nothing_else_does() {
        registry.forEach((key, access) -> assertThat(key.contains(" /internal/"))
                .as(key + " is " + access).isEqualTo(access == Access.INTERNAL));
    }

    @Test
    void the_public_answers_are_reads() {
        registry.forEach((key, access) -> {
            if (access == Access.PUBLIC) {
                assertThat(key).startsWith("GET ");
            }
        });
    }
}
