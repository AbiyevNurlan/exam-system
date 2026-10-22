package com.examportal.exam.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Ids of this session only for the items of ORDERING and the right sides of MATCHING. The real option ids grow in
 * the order the options were written, which is the right order of ORDERING and the order of the left sides of
 * MATCHING: sent as they are, sorting them would give the answer away. A token is an HMAC of the session and the
 * option (48 bits, safe as a JavaScript number), so it cannot be computed without the key, is the same every time
 * the session asks (cached questions, a resumed session) and needs nothing stored.
 */
@Slf4j
@Component
public class AnswerTokens {

    private static final long MASK = (1L << 48) - 1;

    private final SecretKeySpec key;

    public AnswerTokens(@Value("${exam.answer-token-secret:${security.jwt.secret-key:}}") String secret) {
        byte[] bytes;
        if (secret == null || secret.isBlank()) {
            // Tests and a stack without JWT_SECRET: valid until this service restarts
            log.warn("No exam.answer-token-secret: ordering and matching ids change when the service restarts");
            bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
        } else {
            bytes = ("answer-token:" + secret).getBytes(StandardCharsets.UTF_8);
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
    }

    public long token(String sessionId, long optionId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] hash = mac.doFinal((sessionId + ":" + optionId).getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 6; i++) {
                value = (value << 8) | (hash[i] & 0xFF);
            }
            // Never 0: 0 means "no right side" in a stored MATCHING answer
            return (value & MASK) == 0 ? 1 : value & MASK;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /** token -> real option id for the given options of one question */
    public Map<Long, Long> resolver(String sessionId, Collection<Long> optionIds) {
        Map<Long, Long> byToken = new HashMap<>();
        for (Long id : optionIds) {
            byToken.put(token(sessionId, id), id);
        }
        return byToken;
    }
}
