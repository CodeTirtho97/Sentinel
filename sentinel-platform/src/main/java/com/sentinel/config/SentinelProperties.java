package com.sentinel.config;

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

    /** Static dependency graph: service -> the services it calls. */
    private Map<String, List<String>> dependencies = new LinkedHashMap<>();

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

    public static class Evaluation {
        private Duration interval = Duration.ofSeconds(15);
        private int parallelism = 8;
        private int shardIndex = 0;
        private int shardCount = 1;

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
