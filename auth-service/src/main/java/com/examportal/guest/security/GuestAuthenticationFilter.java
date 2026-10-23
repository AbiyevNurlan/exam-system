package com.examportal.guest.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * B13a item 4: signs a guest in from a guest access token, and only on the guest's own paths (GUEST_PATH_PREFIXES):
 * nowhere else can a guest token mean anything. Part of the guest security chain only (GuestSecurityConfig), never a
 * bean of its own, so it is not added to every request. A token that is not a valid guest token changes nothing:
 * the request goes on as it came (unauthenticated, or as the account JwtAuthenticationFilter found).
 */
@Slf4j
public class GuestAuthenticationFilter implements WebFilter {

    /** The paths a guest token works on: the guest's own data now, the paid exams later */
    public static final List<String> GUEST_PATH_PREFIXES = List.of("/api/guest/");

    private static final String BEARER_PREFIX = "Bearer ";

    private final GuestTokenProvider tokens;

    public GuestAuthenticationFilter(GuestTokenProvider tokens) {
        this.tokens = tokens;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (GUEST_PATH_PREFIXES.stream().noneMatch(path::startsWith)) {
            return chain.filter(exchange);
        }
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return chain.filter(exchange);
        }
        GuestPrincipal guest;
        try {
            guest = tokens.parse(header.substring(BEARER_PREFIX.length()));
        } catch (RuntimeException notAGuestToken) {
            // An account's token, an expired or forged one: never logged with its content
            log.debug("Not a guest token on a guest path: {}", notAGuestToken.getClass().getSimpleName());
            return chain.filter(exchange);
        }
        var authentication = new UsernamePasswordAuthenticationToken(guest, null,
                List.of(new SimpleGrantedAuthority("ROLE_GUEST")));
        return chain.filter(exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }
}
