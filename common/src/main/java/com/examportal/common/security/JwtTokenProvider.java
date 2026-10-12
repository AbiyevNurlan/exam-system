package com.examportal.common.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * JWT Token Provider
 * Handles JWT token generation, validation, and parsing.
 */
@Slf4j
@Component
public class JwtTokenProvider {

    @Value("${security.jwt.secret-key:}")
    private String secretKey;

    @Value("${security.jwt.access-token-expiration:900000}")
    private long accessTokenExpiration;

    @Value("${security.jwt.refresh-token-expiration:604800000}")
    private long refreshTokenExpiration;

    @Value("${security.jwt.issuer:exam-system}")
    private String issuer;

    @PostConstruct
    void validateSecretKey() {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET is not set. Provide a base64-encoded secret of at least 32 bytes "
                            + "(e.g. generate one with: openssl rand -base64 32).");
        }
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(secretKey.trim());
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "JWT_SECRET is not valid base64 (" + e.getMessage() + "). Provide a base64-encoded "
                            + "secret of at least 32 bytes (e.g. openssl rand -base64 32).", e);
        }
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET decodes to " + keyBytes.length + " bytes; at least 32 bytes are required.");
        }
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secretKey.trim());
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(UUID userId, String email, String role) {
        return generateToken(userId, email, role, accessTokenExpiration, "ACCESS");
    }

    public String generateRefreshToken(UUID userId, String email, String role) {
        return generateToken(userId, email, role, refreshTokenExpiration, "REFRESH");
    }

    private String generateToken(UUID userId, String email, String role, long expiration, String tokenType) {
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
        } catch (ExpiredJwtException e) {
            log.warn("JWT token is expired: {}", e.getMessage());
        } catch (JwtException e) {
            // Covers malformed, unsupported and tampered (bad signature) tokens
            log.warn("Invalid JWT token: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("JWT claims string is empty: {}", e.getMessage());
        }
        return false;
    }

    /**
     * User id from the token subject. Anything but a canonical UUID is rejected with
     * IllegalArgumentException, which callers treat as an invalid token.
     */
    public UUID getUserIdFromToken(String token) {
        String subject = getClaimFromToken(token, Claims::getSubject);
        if (subject == null) {
            throw new IllegalArgumentException("Token subject is missing");
        }
        UUID userId = UUID.fromString(subject);
        // UUID.fromString is lenient (e.g. "1-1-1-1-1"): only the canonical form is accepted
        if (!userId.toString().equalsIgnoreCase(subject)) {
            throw new IllegalArgumentException("Token subject is not a UUID");
        }
        return userId;
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
