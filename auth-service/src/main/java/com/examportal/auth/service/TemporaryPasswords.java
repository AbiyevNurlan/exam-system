package com.examportal.auth.service;

import java.security.SecureRandom;

/**
 * B13: temporary passwords an administrator hands to a teacher. 16 characters from SecureRandom, at least one
 * capital letter, one small letter, one digit and one sign (so the registration policy holds), without characters
 * that are easy to mix up when read aloud or copied by hand (0/O, 1/l/I). Never logged, never stored as text.
 */
final class TemporaryPasswords {

    static final int LENGTH = 16;
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SIGNS = "-_.!@#%+";
    private static final String ALL = UPPER + LOWER + DIGITS + SIGNS;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TemporaryPasswords() {
    }

    static String generate() {
        char[] password = new char[LENGTH];
        password[0] = pick(UPPER);
        password[1] = pick(LOWER);
        password[2] = pick(DIGITS);
        password[3] = pick(SIGNS);
        for (int i = 4; i < LENGTH; i++) {
            password[i] = pick(ALL);
        }
        // Fisher-Yates, so the guaranteed characters are not always in front
        for (int i = LENGTH - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char swap = password[i];
            password[i] = password[j];
            password[j] = swap;
        }
        return new String(password);
    }

    private static char pick(String alphabet) {
        return alphabet.charAt(RANDOM.nextInt(alphabet.length()));
    }
}
