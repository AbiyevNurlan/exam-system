package com.examportal.common.domain;

import java.text.Collator;
import java.text.ParseException;
import java.text.RuleBasedCollator;
import java.util.Comparator;
import java.util.Locale;

/**
 * Names in Azerbaijani alphabetical order (A B C Ç D E Ə F G Ğ H X I İ J K Q L M N O Ö P R S Ş T U Ü V Y Z),
 * ignoring case and the spaces around them, for lists sorted in Java (results and monitoring by name, whose
 * names come from auth-service). The JDK has no Azerbaijani collation, so the root rules get the
 * Azerbaijani letters added; the same order as az_ci in the databases (ICU "az", strength 2). Capital and
 * small letters are equal (Əli = əli, İlqar = ilqar); ı and i, e and ə, s and ş are different letters.
 * Equal names are told apart by the caller (a second comparator).
 */
public final class AzerbaijaniOrder {

    private static final String LETTERS = "& C < ç, Ç & E < ə, Ə & G < ğ, Ğ"
            + " & H < x, X < ı, I < i, İ & K < q, Q & O < ö, Ö & S < ş, Ş & U < ü, Ü";

    // RuleBasedCollator.compare is synchronized, so one instance serves every thread
    private static final Collator COLLATOR = build();

    /**
     * A to Z in the Azerbaijani alphabet, trimmed; null comes last
     */
    public static final Comparator<String> NAMES =
            Comparator.nullsLast(Comparator.comparing(String::trim, COLLATOR::compare));

    private AzerbaijaniOrder() {
    }

    /**
     * Z to A; null still comes last
     */
    public static Comparator<String> names(boolean ascending) {
        return ascending ? NAMES : Comparator.nullsLast(Comparator.comparing(String::trim, COLLATOR::compare).reversed());
    }

    private static Collator build() {
        try {
            String root = ((RuleBasedCollator) Collator.getInstance(Locale.ROOT)).getRules();
            RuleBasedCollator collator = new RuleBasedCollator(root + LETTERS);
            // Letters and accents count, the case does not
            collator.setStrength(Collator.SECONDARY);
            return collator;
        } catch (ParseException e) {
            throw new IllegalStateException("Azerbaijani collation rules", e);
        }
    }
}
