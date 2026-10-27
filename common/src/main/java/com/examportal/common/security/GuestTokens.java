package com.examportal.common.security;

import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * B13a: the access tokens of guest participants (role GUEST) are signed with their own key, derived from JWT_SECRET
 * with HMAC-SHA256 over a fixed label. No new secret to configure, and a guest token is not a token at all for
 * JwtAuthenticationFilter and JwtTokenProvider (they verify with JWT_SECRET itself, so the signature fails): on every
 * existing endpoint of every service a guest token is exactly what no token is. Only auth-service reads guest tokens,
 * on its guest paths, with this key. The other way round, a token of an account fails this key.
 *
 * A guest token carries iss = ISSUER, role = ROLE and type = ACCESS_TYPE as well, so that a token of the wrong kind is
 * refused even if a key were ever shared by mistake.
 */
public final class GuestTokens {

    public static final String ROLE = "GUEST";
    public static final String ISSUER = "exam-system-guest";
    public static final String ACCESS_TYPE = "GUEST_ACCESS";
    public static final String CODE_CLAIM = "code";

    private static final String LABEL = "exam-system/b13a/guest-access-token/v1";

    private GuestTokens() {
    }

    /** Who a valid guest access token belongs to */
    public record GuestClaims(java.util.UUID id, int participantCode) {
    }

    /**
     * B13b: the guest of a valid guest access token, for a service other than auth-service (exam-service: orders,
     * paid exams). The same rules as GuestTokenProvider of auth-service: the guest key, issuer, role, type, a canonical
     * UUID subject and the code; io.jsonwebtoken.JwtException or IllegalArgumentException for anything else.
     */
    public static GuestClaims parse(SecretKey key, String token) {
        io.jsonwebtoken.Claims claims = io.jsonwebtoken.Jwts.parser()
                .verifyWith(key)
                .requireIssuer(ISSUER)
                .require("role", ROLE)
                .require("type", ACCESS_TYPE)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        String subject = claims.getSubject();
        if (subject == null) {
            throw new IllegalArgumentException("Token subject is missing");
        }
        java.util.UUID id = java.util.UUID.fromString(subject);
        if (!id.toString().equalsIgnoreCase(subject)) {
            throw new IllegalArgumentException("Token subject is not a UUID");
        }
        Integer code = claims.get(CODE_CLAIM, Integer.class);
        if (code == null) {
            throw new io.jsonwebtoken.JwtException("Token has no participant code");
        }
        return new GuestClaims(id, code);
    }

    /**
     * The signing key of guest tokens for a JWT_SECRET (base64, as JwtTokenProvider reads it)
     */
    public static SecretKey signingKey(String jwtSecretBase64) {
        byte[] secret = Decoders.BASE64.decode(jwtSecretBase64.trim());
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Keys.hmacShaKeyFor(mac.doFinal(LABEL.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
