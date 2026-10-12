package com.examportal.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Internal Service Auth
 * Shared-secret check for the few service-to-service endpoints under /internal/**.
 * Those endpoints are not routed by the gateway; the secret (INTERNAL_SERVICE_SECRET, from the
 * environment) additionally protects them where a service port is reachable directly.
 */
public final class InternalServiceAuth {

    public static final String HEADER = "X-Internal-Secret";
    public static final int MIN_SECRET_LENGTH = 32;

    private InternalServiceAuth() {
    }

    /**
     * A missing or short secret means the internal endpoints are switched off, never open
     */
    public static boolean isConfigured(String secret) {
        return secret != null && secret.length() >= MIN_SECRET_LENGTH;
    }

    /**
     * Constant-time comparison; false when no usable secret is configured
     */
    public static boolean matches(String provided, String expected) {
        if (!isConfigured(expected) || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}