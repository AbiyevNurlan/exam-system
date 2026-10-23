package com.examportal.guest.security;

import com.examportal.common.security.GuestTokens;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;

/**
 * B13a item 4: access tokens of guests. Role GUEST, subject the guest id, 30 minutes, signed with the guest key
 * (GuestTokens: derived from JWT_SECRET, so no account token verifies here and no guest token anywhere else).
 * A token is accepted only with the guest issuer, role and type.
 */
@Component
public class GuestTokenProvider {

    @Value("${security.jwt.secret-key:}")
    private String jwtSecret;

    @Value("${guest.access-token-minutes:30}")
    private long accessTokenMinutes;

    private SecretKey key;

    public GuestTokenProvider() {
    }

    /** For tests: a provider with its own secret and lifetime */
    public GuestTokenProvider(String jwtSecret, long accessTokenMinutes) {
        this.jwtSecret = jwtSecret;
        this.accessTokenMinutes = accessTokenMinutes;
        init();
    }

    @PostConstruct
    void init() {
        // JwtTokenProvider already refuses an empty or short JWT_SECRET at start; this only derives the guest key
        key = GuestTokens.signingKey(jwtSecret);
    }

    public Duration accessTokenLifetime() {
        return Duration.ofMinutes(accessTokenMinutes);
    }

    public String issueAccessToken(UUID guestId, int participantCode) {
        Date now = new Date();
        return Jwts.builder()
                .subject(guestId.toString())
                .issuer(GuestTokens.ISSUER)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTokenLifetime().toMillis()))
                .claim("role", GuestTokens.ROLE)
                .claim("type", GuestTokens.ACCESS_TYPE)
                .claim(GuestTokens.CODE_CLAIM, participantCode)
                .signWith(key)
                .compact();
    }

    /**
     * The guest of a valid guest access token; JwtException or IllegalArgumentException for anything else (another
     * key, expired, another issuer, role or type, a subject that is not a canonical UUID)
     */
    public GuestPrincipal parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(GuestTokens.ISSUER)
                .require("role", GuestTokens.ROLE)
                .require("type", GuestTokens.ACCESS_TYPE)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        String subject = claims.getSubject();
        if (subject == null) {
            throw new IllegalArgumentException("Token subject is missing");
        }
        UUID id = UUID.fromString(subject);
        if (!id.toString().equalsIgnoreCase(subject)) {
            throw new IllegalArgumentException("Token subject is not a UUID");
        }
        Integer code = claims.get(GuestTokens.CODE_CLAIM, Integer.class);
        if (code == null) {
            throw new JwtException("Token has no participant code");
        }
        return new GuestPrincipal(id, code);
    }
}
