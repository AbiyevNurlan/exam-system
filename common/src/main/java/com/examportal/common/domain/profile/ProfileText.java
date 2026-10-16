package com.examportal.common.domain.profile;

import com.examportal.common.exception.ValidationException;

import java.util.regex.Pattern;

/**
 * Free text of a participant profile, as it is stored: HTML removed, control characters removed,
 * runs of white space made one space, trimmed. Empty becomes null. The frontend escapes text anyway;
 * this keeps markup out of the database, the CSV exports and every other reader.
 */
public final class ProfileText {

    // A tag or a comment: <b>, </script>, <img src=x onerror=...>, <!-- ... -->
    private static final Pattern TAG = Pattern.compile("<!--.*?-->|<[^>]*>", Pattern.DOTALL);
    // What is left of a broken tag, and characters that only make sense in markup or scripts
    private static final Pattern MARKUP = Pattern.compile("[<>\"`]");
    // C0 and C1 control characters, zero-width and bidi controls
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    // A script URL is not a name of anything
    private static final Pattern SCRIPT_URL = Pattern.compile("(?i)\\b(javascript|vbscript|data)\\s*:");

    private ProfileText() {
    }

    /**
     * The cleaned text, or null when nothing is left. Longer than max after cleaning is a 400 on the field.
     */
    public static String clean(String field, String value, int max) {
        if (value == null) {
            return null;
        }
        String text = TAG.matcher(value).replaceAll(" ");
        text = MARKUP.matcher(text).replaceAll("");
        text = CONTROL.matcher(text).replaceAll(" ");
        text = SCRIPT_URL.matcher(text).replaceAll("$1 ");
        text = SPACE.matcher(text).replaceAll(" ").trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > max) {
            throw new ValidationException(field, "must be at most " + max + " characters");
        }
        return text;
    }
}
