package com.examportal.common.utils;

/**
 * Log Masking Utilities
 * Keeps personal data out of log lines.
 */
public final class LogMaskUtils {

    private LogMaskUtils() {
    }

    /**
     * Mask an email for logging, keeping only the first character and the domain
     */
    public static String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        int at = trimmed.indexOf('@');
        return at < 1 ? "***" : trimmed.charAt(0) + "***" + trimmed.substring(at);
    }
}
