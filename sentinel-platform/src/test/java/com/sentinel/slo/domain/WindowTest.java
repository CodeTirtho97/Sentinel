package com.sentinel.slo.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WindowTest {

    private static final Duration H1 = Duration.ofHours(1);
    private static final Duration M5 = Duration.ofMinutes(5);

    @Test
    void acceptsAShortWindowStrictlyInsideTheLongOne() {
        assertThatCode(() -> new Window(Severity.CRITICAL, H1, M5, 14.4)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAShortWindowEqualToTheLongOne() {
        assertThatThrownBy(() -> new Window(Severity.CRITICAL, H1, H1, 14.4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shorter");
    }

    @Test
    void rejectsAShortWindowLongerThanTheLongOne() {
        assertThatThrownBy(() -> new Window(Severity.CRITICAL, M5, H1, 14.4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "burnThreshold={0} is rejected")
    @ValueSource(doubles = {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsNonPositiveThresholds(double threshold) {
        assertThatThrownBy(() -> new Window(Severity.CRITICAL, H1, M5, threshold))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroLengthWindows() {
        assertThatThrownBy(() -> new Window(Severity.CRITICAL, Duration.ZERO, Duration.ZERO, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingSeverity() {
        assertThatThrownBy(() -> new Window(null, H1, M5, 14.4)).isInstanceOf(NullPointerException.class);
    }
}
