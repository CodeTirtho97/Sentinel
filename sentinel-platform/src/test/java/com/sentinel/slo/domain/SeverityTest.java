package com.sentinel.slo.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SeverityTest {

    @Test
    void ranksCriticalAboveHighAboveMedium() {
        assertThat(Severity.CRITICAL.rank()).isGreaterThan(Severity.HIGH.rank());
        assertThat(Severity.HIGH.rank()).isGreaterThan(Severity.MEDIUM.rank());
    }

    @ParameterizedTest(name = "max({0}, {1}) = {2}")
    @CsvSource({
        "CRITICAL, MEDIUM,   CRITICAL",
        "MEDIUM,   CRITICAL, CRITICAL",
        "HIGH,     MEDIUM,   HIGH",
        "HIGH,     HIGH,     HIGH",
        "MEDIUM,   MEDIUM,   MEDIUM",
    })
    void maxPicksTheMoreUrgent(Severity a, Severity b, Severity expected) {
        assertThat(Severity.max(a, b)).isEqualTo(expected);
    }

    @Test
    void maxToleratesNullSoItCanFoldOverAnEmptySet() {
        assertThat(Severity.max(null, Severity.HIGH)).isEqualTo(Severity.HIGH);
        assertThat(Severity.max(Severity.HIGH, null)).isEqualTo(Severity.HIGH);
        assertThat(Severity.max(null, null)).isNull();
    }

    @ParameterizedTest(name = "{0}.isAtLeast({1}) = {2}")
    @CsvSource({
        "CRITICAL, HIGH,     true",
        "HIGH,     HIGH,     true",
        "MEDIUM,   HIGH,     false",
        "MEDIUM,   CRITICAL, false",
    })
    void isAtLeastComparesByRank(Severity self, Severity other, boolean expected) {
        assertThat(self.isAtLeast(other)).isEqualTo(expected);
    }
}
