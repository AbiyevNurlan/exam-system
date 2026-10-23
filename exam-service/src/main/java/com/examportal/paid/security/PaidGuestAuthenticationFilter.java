package com.examportal.paid.security;

import com.examportal.common.security.GuestTokens;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.util.List;

/**
 * B13b: signs a guest in from a guest access token (B13a, the guest key) on the guest paths of exam-service only
 * (/api/guest/). Part of the paid security chain, never a bean of its own. Anything that is not a valid guest token
 * changes nothing (and is never logged with its content).
 */
@Slf4j
public class PaidGuestAuthenticationFilter implements WebFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final SecretKey key;

    public PaidGuestAuthenticationFilter(SecretKey key) {
        this.key = key;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith("/api/guest/")) {
            return chain.filter(exchange);
        }
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return chain.filter(exchange);
        }
        GuestTokens.GuestClaims claims;
        try {
            claims = GuestTokens.parse(key, header.substring(BEARER_PREFIX.length()));
        } catch (RuntimeException notAGuestToken) {
            log.debug("Not a guest token on a guest path: {}", notAGuestToken.getClass().getSimpleName());
            return chain.filter(exchange);
        }
        var authentication = new UsernamePasswordAuthenticationToken(new PaidGuest(claims.id(), claims.participantCode()), null,
                List.of(new SimpleGrantedAuthority("ROLE_GUEST")));
        return chain.filter(exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }
}
