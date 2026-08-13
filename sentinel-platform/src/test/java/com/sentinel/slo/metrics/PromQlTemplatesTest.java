package com.sentinel.slo.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sentinel.slo.domain.SloType;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PromQlTemplatesTest {

    @ParameterizedTest(name = "{0}s -> {1}")
    @CsvSource({
        "60,     1m",
        "120,    2m",
        "300,    5m",
        "900,    15m",
        "1800,   30m",
        "3600,   1h",
        "21600,  6h",
        "259200, 3d",
        "45,     45s",
    })
    void windowLabelMatchesTheRecordingRuleNames(long seconds, String expected) {
        assertThat(PromQlTemplates.windowLabel(Duration.ofSeconds(seconds))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}ms -> le=\"{1}\"")
    @CsvSource({"100, 0.1", "250, 0.25", "500, 0.5", "1000, 1.0", "2000, 2.0"})
    void bucketLabelMatchesMicrometersSecondsFormatting(int ms, String expected) {
        assertThat(PromQlTemplates.bucketLabel(ms)).isEqualTo(expected);
    }

    @Test
    void availabilitySelectsThePrecomputedRatioRule() {
        assertThat(PromQlTemplates.ratio(SloType.AVAILABILITY, null, Duration.ofMinutes(5)))
                .isEqualTo("slo:error_ratio:5m");
    }

    @Test
    void latencySelectsTheBucketOnTheRatioRule() {
        assertThat(PromQlTemplates.ratio(SloType.LATENCY, 500, Duration.ofMinutes(5)))
                .isEqualTo("slo:latency_ratio:5m{le=\"0.5\"}");
    }

    @Test
    void latencyWithoutAThresholdIsARejectedQuery() {
        assertThatThrownBy(() -> PromQlTemplates.ratio(SloType.LATENCY, null, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countAndCoverageRulesFollowTheSameNaming() {
        assertThat(PromQlTemplates.requests(Duration.ofHours(1))).isEqualTo("slo:requests:1h");
        assertThat(PromQlTemplates.samples(Duration.ofHours(1))).isEqualTo("slo:samples:1h");
        assertThat(PromQlTemplates.budgetErrors(Duration.ofDays(30))).isEqualTo("slo:errors:30d");
        assertThat(PromQlTemplates.budgetTotal(Duration.ofDays(30))).isEqualTo("slo:total:30d");
    }

    @Test
    void rejectsNonPositiveWindows() {
        assertThatThrownBy(() -> PromQlTemplates.windowLabel(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
