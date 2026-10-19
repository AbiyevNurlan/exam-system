package com.examportal.common.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B11b: the Azerbaijani alphabet in Java, the same order as az_ci (ICU) in the databases
 */
class AzerbaijaniOrderTest {

    @Test
    void theAlphabet() {
        List<String> letters = new ArrayList<>(List.of("Z", "Y", "V", "Ü", "U", "T", "Ş", "S", "R", "P", "Ö", "O", "N", "M", "L",
                "Q", "K", "J", "İ", "I", "X", "H", "Ğ", "G", "F", "Ə", "E", "D", "Ç", "C", "B", "A"));
        letters.sort(AzerbaijaniOrder.NAMES);
        assertThat(String.join("", letters)).isEqualTo("ABCÇDEƏFGĞHXIİJKQLMNOÖPRSŞTUÜVYZ");
    }

    @Test
    void namesIgnoringCaseAndSpaces() {
        List<String> names = new ArrayList<>(Arrays.asList("Zaur", " Üzeyir", "Şəkər", "Ömər", "İlqar", "Işıq", "Xanım",
                "Həsən", "Ğəni", "Əli", "Çingiz", "Elçin", "Cavid", "Qasım", "Kamal", "Lalə", null, "Aydın"));
        names.sort(AzerbaijaniOrder.NAMES);
        assertThat(names).containsExactly("Aydın", "Cavid", "Çingiz", "Elçin", "Əli", "Ğəni", "Həsən", "Xanım", "Işıq",
                "İlqar", "Kamal", "Qasım", "Lalə", "Ömər", "Şəkər", " Üzeyir", "Zaur", null);

        assertThat(AzerbaijaniOrder.NAMES.compare("əli", "ƏLİ")).isZero();
        assertThat(AzerbaijaniOrder.NAMES.compare("ilqar", "İLQAR")).isZero();
        assertThat(AzerbaijaniOrder.NAMES.compare("ılqar", "ILQAR")).isZero();
        // ı and i, e and ə, s and ş are different letters
        assertThat(AzerbaijaniOrder.NAMES.compare("ılqar", "ilqar")).isNegative();
        assertThat(AzerbaijaniOrder.NAMES.compare("Elmar", "Əli")).isNegative();
        assertThat(AzerbaijaniOrder.NAMES.compare("Sona", "Şahin")).isNegative();

        List<String> down = new ArrayList<>(Arrays.asList("Əli", null, "Zaur", "Aydın"));
        down.sort(AzerbaijaniOrder.names(false));
        assertThat(down).containsExactly("Zaur", "Əli", "Aydın", null);
    }
}
