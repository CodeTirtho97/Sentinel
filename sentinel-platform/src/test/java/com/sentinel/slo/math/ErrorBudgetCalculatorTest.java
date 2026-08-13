package com.sentinel.slo.math;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ErrorBudgetCalculatorTest {

    private static final Offset<Double> TOLERANCE = Offset.offset(1e-9);

    @ParameterizedTest(name = "{0} errors in {1} at objective {2} leaves {3}")
    @CsvSource({
        // objective 0.999 over 1,000,000 requests allows 1,000 errors.
        "0,      1000000, 0.999, 1.0",
        "250,    1000000, 0.999, 0.75",
        "500,    1000000, 0.999, 0.5",
        "1000,   1000000, 0.999, 0.0",
        // objective 0.99 over 10,000 requests allows 100 errors.
        "50,     10000,   0.99,  0.5",
        "100,    10000,   0.99,  0.0",
    })
    void reportsRemainingBudget(long errors, long total, double objective, double expected) {
        assertThat(ErrorBudgetCalculator.remaining(errors, total, objective)).isCloseTo(expected, TOLERANCE);
    }

    @Test
    void clampsToZeroWhenTheBudgetIsOverspent() {
        assertThat(ErrorBudgetCalculator.remaining(5_000, 1_000_000, 0.999)).isEqualTo(0.0);
    }

    @Test
    void reportsFullBudgetWhenTheWindowSawNoTraffic() {
        assertThat(ErrorBudgetCalculator.remaining(0, 0, 0.999)).isEqualTo(1.0);
    }

    @Test
    void neverDividesByZeroWhenTotalIsZero() {
        assertThat(ErrorBudgetCalculator.remaining(0, 0, 0.999)).isNotNaN();
    }

    @ParameterizedTest(name = "objective={0} is rejected")
    @ValueSource(doubles = {1.0, 0.0, -0.5, 2.0, Double.NaN})
    void rejectsInvalidObjectives(double objective) {
        assertThatThrownBy(() -> ErrorBudgetCalculator.remaining(1, 100, objective))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "errors={0} total={1} is rejected")
    @CsvSource({"-1, 100", "1, -100"})
    void rejectsNegativeCounts(long errors, long total) {
        assertThatThrownBy(() -> ErrorBudgetCalculator.remaining(errors, total, 0.999))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
