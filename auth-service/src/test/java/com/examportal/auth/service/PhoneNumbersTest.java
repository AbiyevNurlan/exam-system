package com.examportal.auth.service;

import com.examportal.common.domain.PhoneNumbers;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B10: the profile phone is an Azerbaijani number stored as E.164 (+994 and 9 digits)
 */
class PhoneNumbersTest {

    @Test
    void theUsualWaysOfWritingANumberBecomeE164() {
        for (String typed : new String[]{"+994501234567", "+994 50 123 45 67", "994501234567", "0501234567",
                "050 123 45 67", "(050) 123-45-67", "501234567", "+994.50.123.45.67"}) {
            assertThat(PhoneNumbers.toE164(typed)).as(typed).isEqualTo("+994501234567");
        }
        assertThat(PhoneNumbers.toE164("012 555 12 34")).isEqualTo("+994125551234");
    }

    @Test
    void anEmptyPhoneIsNoPhone() {
        assertThat(PhoneNumbers.toE164(null)).isNull();
        assertThat(PhoneNumbers.toE164("   ")).isNull();
    }

    @Test
    void otherNumbersAreRefused() {
        for (String wrong : new String[]{"+7 912 345 67 89", "+90 532 123 45 67", "50123456", "05012345678", "+99450123456",
                "+9940501234567", "0001234567", "abc", "+994 50 12a 45 67", "+"}) {
            assertThatThrownBy(() -> PhoneNumbers.toE164(wrong)).as(wrong).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
