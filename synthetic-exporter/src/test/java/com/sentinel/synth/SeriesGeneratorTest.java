package com.sentinel.synth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The exposition has to be exactly what the recording rules select, or the load test measures an
 * evaluator querying an empty vector and reports a wonderfully fast cycle time.
 */
class SeriesGeneratorTest {

    /** A clock the test advances by hand, so counter accounting is checked rather than timed. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-08-06T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }

    private static SyntheticProperties props(int services, int chainLength) {
        var p = new SyntheticProperties();
        p.setServices(services);
        p.setChainLength(chainLength);
        p.setRps(20);
        return p;
    }

    @Test
    @DisplayName("emits the series and labels the recording rules select on")
    void emitsExpectedSeries() {
        var clock = new TestClock();
        var generator = new SeriesGenerator(props(10, 5), clock);
        clock.advanceSeconds(60);

        String scrape = generator.scrape();

        assertThat(scrape).contains("http_server_requests_seconds_count{service=\"synth-c000-s0\",status=\"500\"}");
        assertThat(scrape).contains("http_server_requests_seconds_count{service=\"synth-c000-s0\",status=\"200\"}");
        // The le labels must match PromQlTemplates.bucketLabel exactly, or the latency rule
        // selects nothing and returns an empty vector rather than an error.
        assertThat(scrape).contains("le=\"0.1\"").contains("le=\"0.25\"").contains("le=\"0.5\"");
        assertThat(scrape).contains("le=\"1.0\"").contains("le=\"2.0\"").contains("le=\"+Inf\"");
        assertThat(scrape).contains("http_server_requests_seconds_sum{service=\"synth-c000-s0\"}");
    }

    @Test
    @DisplayName("counters only ever move forward, including across a flip into breach")
    void countersAreMonotonicThroughAStateChange() {
        var clock = new TestClock();
        var generator = new SeriesGenerator(props(5, 5), clock);

        clock.advanceSeconds(60);
        double before = countFor(generator.scrape(), "synth-c000-s0", "200");

        // The whole reason counters accumulate forward instead of being recomputed: a mid-run
        // state change must not rewrite history into a counter reset.
        generator.applyBreachFraction(1.0);
        clock.advanceSeconds(60);
        double after = countFor(generator.scrape(), "synth-c000-s0", "200");

        assertThat(after).isGreaterThan(before);
    }

    @Test
    @DisplayName("a breaching service produces an error ratio far above a 0.999 objective's budget")
    void breachingServiceExceedsTheBudget() {
        var clock = new TestClock();
        var generator = new SeriesGenerator(props(5, 5), clock);
        generator.applyBreachFraction(1.0);
        clock.advanceSeconds(300);

        String scrape = generator.scrape();
        double errors = countFor(scrape, "synth-c000-s0", "500");
        double ok = countFor(scrape, "synth-c000-s0", "200");

        // 0.30 against a 0.001 error budget is a burn rate of roughly 300 — unambiguously CRITICAL.
        assertThat(errors / (errors + ok)).isGreaterThan(0.2);
    }

    @Test
    @DisplayName("a healthy service stays comfortably inside the budget")
    void healthyServiceDoesNotBreach() {
        var clock = new TestClock();
        var generator = new SeriesGenerator(props(5, 5), clock);
        clock.advanceSeconds(300);

        String scrape = generator.scrape();
        double errors = countFor(scrape, "synth-c000-s0", "500");
        double ok = countFor(scrape, "synth-c000-s0", "200");

        // Below the 0.001 budget, so burn rate is under 1 and nothing fires.
        assertThat(errors / (errors + ok)).isLessThan(0.001);
    }

    @Test
    @DisplayName("chains form a line, so a breach at the leaf has somewhere to cascade")
    void topologyIsAChainPerGroup() {
        var generator = new SeriesGenerator(props(10, 5), new TestClock());

        var topology = generator.topology();

        assertThat(topology).hasSize(10);
        assertThat(topology.get("synth-c000-s0")).containsExactly("synth-c000-s1");
        assertThat(topology.get("synth-c000-s3")).containsExactly("synth-c000-s4");
        // The leaf calls nothing — it is where a cascade starts.
        assertThat(topology.get("synth-c000-s4")).isEmpty();
        // Chains are disconnected from each other, so two breaches in different chains must stay
        // two incidents. Without that the collapse ratio would be meaningless.
        assertThat(topology.get("synth-c001-s0")).containsExactly("synth-c001-s1");
    }

    @Test
    @DisplayName("breaching whole chains is what produces a collapse ratio above 1:1")
    void breachAppliesToWholeChains() {
        var generator = new SeriesGenerator(props(20, 5), new TestClock());

        // Four chains of five; half of them breaking is ten services in two incidents.
        assertThat(generator.applyBreachFraction(0.5)).isEqualTo(10);
        assertThat(generator.applyBreachFraction(0.0)).isZero();
        assertThat(generator.applyBreachFraction(1.0)).isEqualTo(20);
    }

    private static double countFor(String scrape, String service, String status) {
        String needle = "http_server_requests_seconds_count{service=\"" + service + "\",status=\"" + status + "\"} ";
        int at = scrape.indexOf(needle);
        assertThat(at).as("series %s status=%s present", service, status).isNotNegative();
        int end = scrape.indexOf('\n', at);
        return Double.parseDouble(scrape.substring(at + needle.length(), end).trim());
    }
}
