package com.sentinel.slo.math;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sentinel.slo.domain.Severity;
import com.sentinel.slo.domain.Window;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BurnRateCalculatorTest {

    private static final Duration H1 = Duration.ofHours(1);
    private static final Duration M5 = Duration.ofMinutes(5);
    private static final Duration H6 = Duration.ofHours(6);
    private static final Duration M30 = Duration.ofMinutes(30);
    private static final Duration D3 = Duration.ofDays(3);

    private static final List<Window> WINDOWS = List.of(
            new Window(Severity.CRITICAL, H1, M5, 14.4),
            new Window(Severity.HIGH, H6, M30, 6.0),
            new Window(Severity.MEDIUM, D3, H6, 1.0));

    private final BurnRateCalculator calculator = new BurnRateCalculator(WINDOWS);

    /** A healthy, fully populated sample at the given error ratio. */
    private static WindowSample sample(double errorRatio) {
        return new WindowSample(errorRatio, 100_000L, 1.0);
    }

    /** The same ratio on every window, so the severity table alone decides the outcome. */
    private static Map<Duration, WindowSample> uniform(double errorRatio) {
        WindowSample s = sample(errorRatio);
        Map<Duration, WindowSample> samples = new HashMap<>();
        samples.put(H1, s);
        samples.put(M5, s);
        samples.put(H6, s);
        samples.put(M30, s);
        samples.put(D3, s);
        return samples;
    }

    @Nested
    @DisplayName("severity table")
    class SeverityTable {

        @ParameterizedTest(name = "errorRatio={0} objective={1} stays Ok")
        @CsvSource({
            "0.0,     0.999",
            "0.0001,  0.999",
            "0.0005,  0.999",
            "0.00099, 0.999",
        })
        void staysOkBelowEveryThreshold(double errorRatio, double objective) {
            assertThat(calculator.evaluate(objective, uniform(errorRatio))).isInstanceOf(BurnRateResult.Ok.class);
        }

        @ParameterizedTest(name = "errorRatio={0} objective={1} -> {2}")
        @CsvSource({
            "0.001,   0.999, MEDIUM",
            "0.003,   0.999, MEDIUM",
            "0.00599, 0.999, MEDIUM",
            "0.006,   0.999, HIGH",
            "0.010,   0.999, HIGH",
            "0.0143,  0.999, HIGH",
            "0.0144,  0.999, CRITICAL",
            "0.100,   0.999, CRITICAL",
            "0.500,   0.999, CRITICAL",
        })
        void breachesAtTheExpectedSeverity(double errorRatio, double objective, Severity expected) {
            BurnRateResult result = calculator.evaluate(objective, uniform(errorRatio));

            assertThat(result).isInstanceOf(BurnRateResult.Breach.class);
            assertThat(((BurnRateResult.Breach) result).severity()).isEqualTo(expected);
        }

        @Test
        void returnsTheHighestSeverityWhenSeveralTrip() {
            // burn 500 clears 14.4, 6.0 and 1.0 at once.
            BurnRateResult result = calculator.evaluate(0.999, uniform(0.5));

            assertThat(((BurnRateResult.Breach) result).severity()).isEqualTo(Severity.CRITICAL);
        }

        @Test
        void firesExactlyAtTheThreshold() {
            // 14.4 is the CRITICAL threshold; landing precisely on it must fire, not fall through.
            BurnRateResult result = calculator.evaluate(0.999, uniform(0.0144));

            assertThat(((BurnRateResult.Breach) result).severity()).isEqualTo(Severity.CRITICAL);
        }

        @Test
        void reportsBurnRatesFromTheMostUrgentUsableWindow() {
            BurnRateResult result = calculator.evaluate(0.999, uniform(0.0005));

            BurnRateResult.Ok ok = (BurnRateResult.Ok) result;
            assertThat(ok.longBurn()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(ok.shortBurn()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    @Nested
    @DisplayName("multi-window rule")
    class MultiWindowRule {

        @Test
        void doesNotFireWhenOnlyTheLongWindowExceeds() {
            // Long window still hot, short window recovered — the alert must stop.
            Map<Duration, WindowSample> samples = Map.of(H1, sample(0.02), M5, sample(0.0));

            assertThat(calculator.evaluate(0.999, samples)).isInstanceOf(BurnRateResult.Ok.class);
        }

        @Test
        void doesNotFireWhenOnlyTheShortWindowExceeds() {
            // A fresh spike that has not yet moved the long window.
            Map<Duration, WindowSample> samples = Map.of(H1, sample(0.0), M5, sample(0.02));

            assertThat(calculator.evaluate(0.999, samples)).isInstanceOf(BurnRateResult.Ok.class);
        }

        @Test
        void firesWhenBothWindowsExceed() {
            Map<Duration, WindowSample> samples = Map.of(H1, sample(0.02), M5, sample(0.02));

            BurnRateResult result = calculator.evaluate(0.999, samples);

            assertThat(((BurnRateResult.Breach) result).severity()).isEqualTo(Severity.CRITICAL);
        }

        @Test
        void skipsSeveritiesWithMissingSamplesAndEvaluatesTheRest() {
            // No CRITICAL data at all; HIGH is present and hot.
            Map<Duration, WindowSample> samples = Map.of(H6, sample(0.010), M30, sample(0.010));

            BurnRateResult result = calculator.evaluate(0.999, samples);

            assertThat(((BurnRateResult.Breach) result).severity()).isEqualTo(Severity.HIGH);
        }
    }

    @Nested
    @DisplayName("insufficient data never becomes a breach")
    class InsufficientData {

        @Test
        void whenNoSamplesAtAll() {
            assertThat(calculator.evaluate(0.999, Map.of())).isInstanceOf(BurnRateResult.InsufficientData.class);
        }

        @Test
        void whenTheWindowSawZeroEvents() {
            WindowSample none = new WindowSample(0.0, 0L, 1.0);
            Map<Duration, WindowSample> samples = Map.of(H1, none, M5, none);

            assertThat(calculator.evaluate(0.999, samples)).isInstanceOf(BurnRateResult.InsufficientData.class);
        }

        @Test
        void whenTooFewEventsToBeMeaningful() {
            WindowSample thin = new WindowSample(1.0, 5L, 1.0);
            Map<Duration, WindowSample> samples = Map.of(H1, thin, M5, thin);

            assertThat(calculator.evaluate(0.999, samples)).isInstanceOf(BurnRateResult.InsufficientData.class);
        }

        @Test
        void whenTheWindowIsOnlyPartiallyCovered() {
            // Service started ten minutes into a one hour window.
            WindowSample partial = new WindowSample(0.5, 100_000L, 0.16);
            Map<Duration, WindowSample> samples = Map.of(H1, partial, M5, sample(0.5));

            assertThat(calculator.evaluate(0.999, samples)).isInstanceOf(BurnRateResult.InsufficientData.class);
        }

        @ParameterizedTest(name = "ratio={0}")
        @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 1.5})
        void whenTheRatioIsNotAUsableProbability(double ratio) {
            WindowSample bad = new WindowSample(ratio, 100_000L, 1.0);
            Map<Duration, WindowSample> samples = Map.of(H1, bad, M5, bad);

            assertThat(calculator.evaluate(0.999, samples)).isInstanceOf(BurnRateResult.InsufficientData.class);
        }

        @Test
        void carriesAReasonForOperators() {
            BurnRateResult result = calculator.evaluate(0.999, Map.of());

            assertThat(((BurnRateResult.InsufficientData) result).reason()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("objective validation")
    class ObjectiveValidation {

        @ParameterizedTest(name = "objective={0} is rejected")
        @ValueSource(doubles = {1.0, 0.0, -0.1, 1.5, Double.NaN, Double.POSITIVE_INFINITY})
        void rejectsObjectivesOutsideTheOpenUnitInterval(double objective) {
            assertThatThrownBy(() -> calculator.evaluate(objective, uniform(0.0)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void objectiveOfOneLeavesNoBudgetAndIsRejectedBeforeAnyDivision() {
            assertThatThrownBy(() -> BurnRateCalculator.errorBudget(1.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("zero error budget");
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @ParameterizedTest(name = "objective={0} -> budget={1}")
        @CsvSource({"0.999, 0.001", "0.99, 0.01", "0.9, 0.1", "0.9999, 0.0001"})
        void errorBudgetIsOneMinusObjective(double objective, double expected) {
            assertThat(BurnRateCalculator.errorBudget(objective))
                    .isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-12));
        }

        @ParameterizedTest(name = "ratio={0} objective={1} -> burn={2}")
        @CsvSource({
            "0.0,    0.999, 0.0",
            "0.001,  0.999, 1.0",
            "0.0144, 0.999, 14.4",
            "0.01,   0.99,  1.0",
        })
        void burnRateIsErrorRatioOverBudget(double ratio, double objective, double expected) {
            assertThat(BurnRateCalculator.burnRate(ratio, objective))
                    .isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        void rejectsAnEmptyWindowTable() {
            assertThatThrownBy(() -> new BurnRateCalculator(List.of())).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void ordersWindowsBySeverityRegardlessOfInputOrder() {
            // MEDIUM first on the way in; CRITICAL must still win on the way out.
            var shuffled = new BurnRateCalculator(List.of(
                    new Window(Severity.MEDIUM, D3, H6, 1.0),
                    new Window(Severity.CRITICAL, H1, M5, 14.4),
                    new Window(Severity.HIGH, H6, M30, 6.0)));

            BurnRateResult result = shuffled.evaluate(0.999, uniform(0.5));

            assertThat(((BurnRateResult.Breach) result).severity()).isEqualTo(Severity.CRITICAL);
        }
    }
}
