package com.examportal.common.domain;

/**
 * Azerbaijani phone numbers in E.164 form: +994 and 9 national digits (+994501234567). Accepted as
 * typed: "+994 50 123 45 67", "994501234567", "050 123 45 67" (trunk 0), "501234567"; spaces,
 * dashes, dots and parentheses are ignored. Not unique and not verified (B10).
 */
public final class PhoneNumbers {

    private static final String COUNTRY = "994";
    private static final int NATIONAL_DIGITS = 9;

    private PhoneNumbers() {
    }

    /**
     * The E.164 form, or null for an empty text; IllegalArgumentException when it is not an
     * Azerbaijani number
     */
    public static String toE164(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String compact = text.replaceAll("[\\s().\\-]", "");
        boolean plus = compact.startsWith("+");
        String digits = plus ? compact.substring(1) : compact;
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("A phone number has digits only");
        }
        String national;
        if (digits.startsWith(COUNTRY) && digits.length() == COUNTRY.length() + NATIONAL_DIGITS) {
            national = digits.substring(COUNTRY.length());
        } else if (plus) {
            throw new IllegalArgumentException("Only Azerbaijani numbers (+994) are accepted");
        } else if (digits.length() == NATIONAL_DIGITS + 1 && digits.startsWith("0")) {
            national = digits.substring(1);
        } else if (digits.length() == NATIONAL_DIGITS) {
            national = digits;
        } else {
            throw new IllegalArgumentException("An Azerbaijani number has 9 digits after +994");
        }
        if (national.startsWith("0")) {
            throw new IllegalArgumentException("An Azerbaijani number does not start with 0 after +994");
        }
        return "+" + COUNTRY + national;
    }
}
