package com.sentinel.config;

import com.sentinel.slo.domain.Severity;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sentinel")
public class SentinelProperties {

    private final Evaluation evaluation = new Evaluation();
    private final Slo slo = new Slo();
    private final Metrics metrics = new Metrics();
    private final Correlation correlation = new Correlation();
    private final Events events = new Events();
    private final Demo demo = new Demo();
    private final Rca rca = new Rca();

    public Rca getRca() {
        return rca;
    }

    /** Bounds on the LLM path, which is the only part of the system that costs money per event. */
    public static class Rca {

        /**
         * Only these severities get a model call when their incident opens.
         *
         * <p>Everything else still gets an RCA on demand from {@code GET /incidents/{id}/rca} — the
         * deterministic timeline summary, which is the same artefact the circuit-breaker fallback
         * produces. What is bounded here is unsolicited model calls, not the feature.
         *
         * <p>Unbounded fan-out is not theoretical: one measured storm opened 7,632 incidents and
         * attempted 7,632 drafts. A free tier of ~30 requests/minute is gone in seconds, after
         * which every incident receives the fallback anyway.
         */
        private List<Severity> draftSeverities = new ArrayList<>(List.of(Severity.CRITICAL));

        public List<Severity> getDraftSeverities() {
            return draftSeverities;
        }

        public void setDraftSeverities(List<Severity> draftSeverities) {
            this.draftSeverities = draftSeverities;
        }
    }

    /** Static dependency graph: service -> the services it calls. */
    private Map<String, List<String>> dependencies = new LinkedHashMap<>();

    /**
     * Load-test only: fetch an additional topology from this URL at startup and merge it in.
     *
     * <p>Empty by default, and it must stay that way outside a load test — a real deployment's
     * topology is configuration, reviewed and version-controlled, not something pulled from a
     * running peer at boot.
     *
     * <p>It exists because the synthetic exporter generates its fleet at runtime from
     * {@code SYNTHETIC_SERVICES}, so the chain edges cannot be written into YAML ahead of time
     * without pinning the fleet size. Without them every synthetic service is an isolated node,
     * every breach is a component of one, and the alert-collapse ratio is exactly 1:1 — the
     * measurement reports the product doing nothing, and is right to.
     */
    private String syntheticTopologyUrl = "";

    public Evaluation getEvaluation() {
        return evaluation;
    }

    public Correlation getCorrelation() {
        return correlation;
    }

    public Events getEvents() {
        return events;
    }

    public Demo getDemo() {
        return demo;
    }

    /**
     * Demo-only wiring: where the fleet lives so the visualiser can drive it.
     *
     * <p>Populated by {@code application-demo.yml} and read only by demo-profile beans. A real
     * deployment leaves it empty and the endpoints that use it do not exist.
     */
    public static class Demo {

        private Map<String, String> fleet = new LinkedHashMap<>();

        public Map<String, String> getFleet() {
            return fleet;
        }

        public void setFleet(Map<String, String> fleet) {
            this.fleet = fleet;
        }
    }

    public Slo getSlo() {
        return slo;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public Map<String, List<String>> getDependencies() {
        return dependencies;
    }

    public void setDependencies(Map<String, List<String>> dependencies) {
        this.dependencies = dependencies;
    }

    public String getSyntheticTopologyUrl() {
        return syntheticTopologyUrl;
    }

    public void setSyntheticTopologyUrl(String syntheticTopologyUrl) {
        this.syntheticTopologyUrl = syntheticTopologyUrl;
    }

    public static class Evaluation {
        private Duration interval = Duration.ofSeconds(15);
        private int parallelism = 8;
        private int shardIndex = 0;
        private int shardCount = 1;

        /**
         * How often an ongoing breach is re-announced.
         *
         * <p>A breach is published when it starts and when its severity moves. Between those, it is
         * re-announced on this interval so downstream can tell "still burning" from "the detector
         * has stopped talking to me" — {@code lastBreachAt} is what auto-resolution reads, so this
         * must stay comfortably below both the correlation window and auto-resolve-after. Startup
         * fails if it does not.
         *
         * <p>Publishing every cycle instead, which is what this replaced, generated 16,000 events a
         * minute at 4,000 breaching SLOs and buried the consumer. Alertmanager's equivalent
         * (repeat_interval) defaults to four hours; two minutes is already generous.
         */
        private Duration breachRepublishInterval = Duration.ofMinutes(2);

        /** Below this many events in a window, the sample is not statistically meaningful. */
        private long minimumEvents = 60;

        /** Fraction of a window that must actually contain data before it can be judged. */
        private double minimumCoverage = 0.75;

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public Duration getBreachRepublishInterval() {
            return breachRepublishInterval;
        }

        public void setBreachRepublishInterval(Duration breachRepublishInterval) {
            this.breachRepublishInterval = breachRepublishInterval;
        }

        public int getParallelism() {
            return parallelism;
        }

        public void setParallelism(int parallelism) {
            this.parallelism = parallelism;
        }

        public int getShardIndex() {
            return shardIndex;
        }

        public void setShardIndex(int shardIndex) {
            this.shardIndex = shardIndex;
        }

        public int getShardCount() {
            return shardCount;
        }

        public void setShardCount(int shardCount) {
            this.shardCount = shardCount;
        }

        public long getMinimumEvents() {
            return minimumEvents;
        }

        public void setMinimumEvents(long minimumEvents) {
            this.minimumEvents = minimumEvents;
        }

        public double getMinimumCoverage() {
            return minimumCoverage;
        }

        public void setMinimumCoverage(double minimumCoverage) {
            this.minimumCoverage = minimumCoverage;
        }
    }

    public static class Slo {
        private Map<String, WindowSpec> windows = new LinkedHashMap<>();

        /** Latency thresholds that match a configured Micrometer histogram bucket. */
        private List<Integer> allowedLatencyThresholdsMs = new ArrayList<>(List.of(100, 250, 500, 1000, 2000));

        public Map<String, WindowSpec> getWindows() {
            return windows;
        }

        public void setWindows(Map<String, WindowSpec> windows) {
            this.windows = windows;
        }

        public List<Integer> getAllowedLatencyThresholdsMs() {
            return allowedLatencyThresholdsMs;
        }

        public void setAllowedLatencyThresholdsMs(List<Integer> allowedLatencyThresholdsMs) {
            this.allowedLatencyThresholdsMs = allowedLatencyThresholdsMs;
        }
    }

    /** One severity row of the burn rate table. Keyed by severity name in YAML. */
    public static class WindowSpec {
        private Duration longWindow;
        private Duration shortWindow;
        private double burnThreshold;

        public Duration getLongWindow() {
            return longWindow;
        }

        public void setLongWindow(Duration longWindow) {
            this.longWindow = longWindow;
        }

        public Duration getShortWindow() {
            return shortWindow;
        }

        public void setShortWindow(Duration shortWindow) {
            this.shortWindow = shortWindow;
        }

        public double getBurnThreshold() {
            return burnThreshold;
        }

        public void setBurnThreshold(double burnThreshold) {
            this.burnThreshold = burnThreshold;
        }
    }

    public static class Events {

        /** {@code kafka} or {@code in-memory}. Selects which EventPublisher bean is created. */
        private String publisher = "kafka";

        public String getPublisher() {
            return publisher;
        }

        public void setPublisher(String publisher) {
            this.publisher = publisher;
        }
    }

    public static class Correlation {

        /** How far back a breach still counts as part of the same event. */
        private Duration window = Duration.ofMinutes(5);

        /** An incident with no member breach for this long is auto-resolved. */
        private Duration autoResolveAfter = Duration.ofMinutes(10);

        /**
         * Skip the auto-resolve sweep while the breach consumer is this far behind.
         *
         * <p>Auto-resolution infers "the problem stopped" from "no breach arrived recently". That
         * inference is only sound when the consumer is current. Under a large storm it is not: the
         * evaluator publishes faster than the consumer drains, {@code lastBreachAt} stays frozen at
         * whatever was last processed, and the sweep closes incidents for services that are still
         * actively failing — a silent all-clear during the exact event the product exists for.
         *
         * <p>Measured: at 2,000 concurrently breaching services the backlog reached 18,310 messages
         * and 4,243 live incidents were auto-resolved, every one at exactly the 600s threshold.
         *
         * <p>Non-zero lag is normal in flight, so this is a tolerance rather than a strict zero. If
         * the consumer is wedged the sweep stops running and incidents accumulate — noisy, visible,
         * and vastly preferable to a dashboard that has quietly gone green mid-outage.
         */
        private long autoResolveMaxLag = 100;

        /** How long a processed eventId is remembered. Longer than any plausible redelivery. */
        private Duration dedupeTtl = Duration.ofHours(24);

        /** TTL on the Redis correlation ZSET. Must exceed the window with room to spare. */
        private Duration recentRetention = Duration.ofMinutes(10);

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public Duration getAutoResolveAfter() {
            return autoResolveAfter;
        }

        public void setAutoResolveAfter(Duration autoResolveAfter) {
            this.autoResolveAfter = autoResolveAfter;
        }

        public long getAutoResolveMaxLag() {
            return autoResolveMaxLag;
        }

        public void setAutoResolveMaxLag(long autoResolveMaxLag) {
            this.autoResolveMaxLag = autoResolveMaxLag;
        }

        public Duration getDedupeTtl() {
            return dedupeTtl;
        }

        public void setDedupeTtl(Duration dedupeTtl) {
            this.dedupeTtl = dedupeTtl;
        }

        public Duration getRecentRetention() {
            return recentRetention;
        }

        public void setRecentRetention(Duration recentRetention) {
            this.recentRetention = recentRetention;
        }
    }

    public static class Metrics {
        private String prometheusBaseUrl = "http://localhost:9090";
        private Duration timeout = Duration.ofSeconds(5);

        /** Used to turn a sample count into a coverage fraction. Must match prometheus.yml. */
        private Duration scrapeInterval = Duration.ofSeconds(15);

        public String getPrometheusBaseUrl() {
            return prometheusBaseUrl;
        }

        public void setPrometheusBaseUrl(String prometheusBaseUrl) {
            this.prometheusBaseUrl = prometheusBaseUrl;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Duration getScrapeInterval() {
            return scrapeInterval;
        }

        public void setScrapeInterval(Duration scrapeInterval) {
            this.scrapeInterval = scrapeInterval;
        }
    }
}
