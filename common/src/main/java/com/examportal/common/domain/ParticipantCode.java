package com.examportal.common.domain;

/**
 * The 7-digit participant code of an account (users.participant_code, auth-service V3). For display
 * and search only: never use it for a permission or security decision, the UUID is the identity.
 *
 * The database gives the codes (next_participant_code()): code = MIN + (A * n + B) mod COUNT for the
 * sequence value n; A shares no factor with COUNT, so the mapping is a bijection and codes never repeat.
 */
public final class ParticipantCode {

    public static final int MIN = 1_000_000;
    public static final int MAX = 9_999_999;
    public static final int COUNT = MAX - MIN + 1;
    public static final long MULTIPLIER = 7_654_321L;
    public static final long OFFSET = 3_141_592L;

    private ParticipantCode() {
    }

    /**
     * The code the database gives for sequence value n (0 .. COUNT - 1); the same formula as the migration
     */
    public static int forSequenceValue(long n) {
        if (n < 0 || n >= COUNT) {
            throw new IllegalArgumentException("Sequence value out of range: " + n);
        }
        return (int) (MIN + (MULTIPLIER * n + OFFSET) % COUNT);
    }

    /**
     * A search text that is exactly a participant code (7 digits, 1000000-9999999), or null.
     * Only an exact code is searched: a part of a code finds nothing.
     */
    public static Integer parse(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.length() != 7 || !trimmed.chars().allMatch(Character::isDigit)) {
            return null;
        }
        int code = Integer.parseInt(trimmed);
        return code >= MIN ? code : null;
    }
}
