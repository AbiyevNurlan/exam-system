package com.examportal.common.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Map;
import java.util.function.Function;

/**
 * JWT Token Provider
 * Handles JWT token generation, validation, and parsing.
 */
@Slf4j
@Component
public class JwtTokenProvider {

    @Value("${security.jwt.secret-key:your-256-bit-secret-key-here-must-be-at-least-32-characters-long}")
    private String secretKey;

    @Value("${security.jwt.access-token-expiration:900000}")
    private long accessTokenExpiration;

    @Value("${security.jwt.refresh-token-expiration:604800000}")
    private long refreshTokenExpiration;

    @Value("${security.jwt.issuer:exam-system}")
    private String issuer;

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(Long userId, String email, String role) {
        return generateToken(userId, email, role, accessTokenExpiration, "ACCESS");
    }

    public String generateRefreshToken(Long userId, String email, String role) {
        return generateToken(userId, email, role, refreshTokenExpiration, "REFRESH");
    }

    private String generateToken(Long userId, String email, String role, long expiration, String tokenType) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(userId.toString())
                .issuer(issuer)
                .issuedAt(now)
                .expiration(expiryDate)
                .claims(Map.of(
                        "email", email,
                        "role", role,
                        "type", tokenType
                ))
                .signWith(getSigningKey())
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (MalformedJwtException e) {
            log.error("Invalid JWT token: {}", e.getMessage());
        } catch (ExpiredJwtException e) {
            log.error("JWT token is expired: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.error("JWT token is unsupported: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.error("JWT claims string is empty: {}", e.getMessage());
        }
        return false;
    }

    public Long getUserIdFromToken(String token) {
        String subject = getClaimFromToken(token, Claims::getSubject);
        return Long.parseLong(subject);
    }

    public String getEmailFromToken(String token) {
        return getClaimFromToken(token, claims -> claims.get("email", String.class));
    }

    public String getRoleFromToken(String token) {
        return getClaimFromToken(token, claims -> claims.get("role", String.class));
    }

    public String getTokenType(String token) {
        return getClaimFromToken(token, claims -> claims.get("type", String.class));
    }

    public Date getExpirationFromToken(String token) {
        return getClaimFromToken(token, Claims::getExpiration);
    }

    public boolean isTokenExpired(String token) {
        Date expiration = getExpirationFromToken(token);
        return expiration.before(new Date());
    }

    private <T> T getClaimFromToken(String token, Function<Claims, T> claimsResolver) {
        Claims claims = getAllClaimsFromToken(token);
        return claimsResolver.apply(claims);
    }

    private Claims getAllClaimsFromToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getAccessTokenExpiration() {
        return accessTokenExpiration;
    }

    public long getRefreshTokenExpiration() {
        return refreshTokenExpiration;
    }
}
