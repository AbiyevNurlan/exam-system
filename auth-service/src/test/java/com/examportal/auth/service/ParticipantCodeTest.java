package com.examportal.auth.service;

import com.examportal.common.domain.ParticipantCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B10: the code formula of auth-service V3 is a bijection of all 9 000 000 sequence values onto
 * 1000000-9999999 (no code twice, no collision check needed) and does not look sequential.
 */
class ParticipantCodeTest {

    @Test
    void everySequenceValueGivesADifferentSevenDigitCodeAndEveryCodeIsReachedOnce() {
        boolean[] seen = new boolean[ParticipantCode.COUNT];
        for (long n = 0; n < ParticipantCode.COUNT; n++) {
            int code = ParticipantCode.forSequenceValue(n);
            assertThat(code).isBetween(ParticipantCode.MIN, ParticipantCode.MAX);
            int slot = code - ParticipantCode.MIN;
            if (seen[slot]) {
                throw new AssertionError("code " + code + " given twice (n = " + n + ")");
            }
            seen[slot] = true;
        }
        for (boolean reached : seen) {
            assertThat(reached).isTrue();
        }
    }

    @Test
    void consecutiveAccountsGetCodesFarApart() {
        for (long n = 0; n < 1000; n++) {
            long gap = Math.abs((long) ParticipantCode.forSequenceValue(n + 1) - ParticipantCode.forSequenceValue(n));
            assertThat(gap).isGreaterThan(1000);
        }
    }

    @Test
    void thereIsNoValueAfterTheLast() {
        assertThatThrownBy(() -> ParticipantCode.forSequenceValue(ParticipantCode.COUNT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParticipantCode.forSequenceValue(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyAnExactSevenDigitCodeIsASearchForACode() {
        assertThat(ParticipantCode.parse("4794913")).isEqualTo(4794913);
        assertThat(ParticipantCode.parse(" 4794913 ")).isEqualTo(4794913);
        assertThat(ParticipantCode.parse("479491")).isNull();
        assertThat(ParticipantCode.parse("47949130")).isNull();
        assertThat(ParticipantCode.parse("0794913")).isNull();
        assertThat(ParticipantCode.parse("47949a3")).isNull();
        assertThat(ParticipantCode.parse("ali@x.test")).isNull();
        assertThat(ParticipantCode.parse(null)).isNull();
    }
}
