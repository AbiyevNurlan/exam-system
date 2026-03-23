package com.examportal.common.utils;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Code Generator Utility
 * Generates various types of random codes and identifiers.
 */
public final class CodeGenerator {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String NUMERIC = "0123456789";

    private CodeGenerator() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Generates a random alphanumeric code of specified length.
     */
    public static String generateAlphanumericCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUMERIC.charAt(SECURE_RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }

    /**
     * Generates a random numeric code of specified length.
     */
    public static String generateNumericCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(NUMERIC.charAt(SECURE_RANDOM.nextInt(NUMERIC.length())));
        }
        return sb.toString();
    }

    /**
     * Generates a secure random token encoded in Base64.
     */
    public static String generateSecureToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Generates an exam access code.
     */
    public static String generateExamCode() {
        return "EX-" + generateAlphanumericCode(8);
    }

    /**
     * Generates a verification code.
     */
    public static String generateVerificationCode() {
        return generateNumericCode(6);
    }

    /**
     * Generates a password reset token.
     */
    public static String generatePasswordResetToken() {
        return generateSecureToken(32);
    }
}
