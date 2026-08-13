package com.sentinel.synth;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * N services' worth of Prometheus series, advanced on every scrape.
 *
 * <p>Counters are accumulated from the time elapsed since the previous scrape rather than
 * recomputed from a start timestamp. Recomputing would be simpler, but flipping a service into
 * breach mid-run would retroactively rewrite its whole history — the counter would jump, Prometheus
 * would read it as a counter reset, and every rate over that window would be wrong. Accumulating
 * forward keeps the series monotonic through a state change, which is the entire scenario the
 * breach-storm measurement is trying to create.
 */
@Component
public class SeriesGenerator {

    private static final Logger log = LoggerFactory.getLogger(SeriesGenerator.class);

    /** Must match the demo fleet's Micrometer buckets, so one SLO definition fits both. */
    private static final double[] BUCKETS = {0.1, 0.25, 0.5, 1.0, 2.0};

    /** Cumulative fraction of requests at or under each bucket, healthy. */
    private static final double[] HEALTHY_CUMULATIVE = {0.90, 0.98, 0.995, 0.999, 0.9999};

    /** The same, in breach: 45% slower than 500ms, which is a burn rate of 450 on a 0.999 objective. */
    private static final double[] BREACH_CUMULATIVE = {0.20, 0.35, 0.55, 0.80, 0.95};

    private static final double HEALTHY_MEAN_SECONDS = 0.06;
    private static final double BREACH_MEAN_SECONDS = 0.85;

    private final SyntheticProperties props;
    private final Clock clock;

    /** One scrape at a time: two concurrent scrapes would each advance the counters. */
    private final ReentrantLock lock = new ReentrantLock();

    private final List<Service> services = new ArrayList<>();
    private long lastAdvanceMillis;

    SeriesGenerator(SyntheticProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        reset();
    }

    /** Mutable per-service counters. Only ever touched under {@link #lock}. */
    private static final class Service {
        final String name;
        final int chain;
        volatile boolean breaching;

        double total;
        double errors;
        double sumSeconds;
        final double[] bucketCounts = new double[BUCKETS.length];

        Service(String name, int chain) {
            this.name = name;
            this.chain = chain;
        }
    }

    /** Rebuilds every service from scratch and zeroes the counters. */
    public final void reset() {
        lock.lock();
        try {
            services.clear();
            int chains = Math.max(1, (int) Math.ceil((double) props.getServices() / props.getChainLength()));
            for (int chain = 0; chain < chains && services.size() < props.getServices(); chain++) {
                for (int depth = 0; depth < props.getChainLength() && services.size() < props.getServices(); depth++) {
                    services.add(new Service(nameOf(chain, depth), chain));
                }
            }
            applyBreachFraction(props.getBreachFraction());
            lastAdvanceMillis = clock.millis();
            log.info("generating {} services in {} chains of {}", services.size(), chains, props.getChainLength());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Puts the first {@code fraction} of chains into breach.
     *
     * <p>Whole chains, not scattered services: a cascade is what correlation is being measured
     * against, and breaking unrelated leaves would produce one incident each and measure nothing.
     *
     * @return the number of services now breaching
     */
    public int applyBreachFraction(double fraction) {
        lock.lock();
        try {
            int chains = services.stream().mapToInt(s -> s.chain).max().orElse(-1) + 1;
            int breaching = (int) Math.round(chains * Math.clamp(fraction, 0.0, 1.0));
            services.forEach(s -> s.breaching = s.chain < breaching);
            int count = (int) services.stream().filter(s -> s.breaching).count();
            log.info("{} of {} chains breaching ({} services)", breaching, chains, count);
            return count;
        } finally {
            lock.unlock();
        }
    }

    /** The Prometheus exposition document, with counters advanced to now. */
    public String scrape() {
        lock.lock();
        try {
            advance();

            var out = new StringBuilder(services.size() * 512);
            out.append("# HELP http_server_requests_seconds Duration of HTTP server request handling\n");
            out.append("# TYPE http_server_requests_seconds histogram\n");

            for (Service service : services) {
                for (int i = 0; i < BUCKETS.length; i++) {
                    out.append("http_server_requests_seconds_bucket{service=\"")
                            .append(service.name)
                            .append("\",le=\"")
                            .append(formatLe(BUCKETS[i]))
                            .append("\"} ")
                            .append(format(service.bucketCounts[i]))
                            .append('\n');
                }
                // +Inf must equal the total, or the histogram is malformed and the latency ratio
                // silently exceeds 1.
                out.append("http_server_requests_seconds_bucket{service=\"")
                        .append(service.name)
                        .append("\",le=\"+Inf\"} ")
                        .append(format(service.total))
                        .append('\n');

                out.append("http_server_requests_seconds_sum{service=\"")
                        .append(service.name)
                        .append("\"} ")
                        .append(format(service.sumSeconds))
                        .append('\n');

                // Split by status so the availability rule's {status=~"5.."} selector has something
                // to match. The rule sums across statuses for the denominator.
                out.append("http_server_requests_seconds_count{service=\"")
                        .append(service.name)
                        .append("\",status=\"200\"} ")
                        .append(format(service.total - service.errors))
                        .append('\n');
                out.append("http_server_requests_seconds_count{service=\"")
                        .append(service.name)
                        .append("\",status=\"500\"} ")
                        .append(format(service.errors))
                        .append('\n');
            }
            return out.toString();
        } finally {
            lock.unlock();
        }
    }

    private void advance() {
        long now = clock.millis();
        double elapsedSeconds = (now - lastAdvanceMillis) / 1000.0;
        lastAdvanceMillis = now;
        if (elapsedSeconds <= 0) {
            return;
        }

        double requests = props.getRps() * elapsedSeconds;
        for (Service service : services) {
            double errorRate = service.breaching ? props.getBreachErrorRate() : props.getErrorRate();
            double[] cumulative = service.breaching ? BREACH_CUMULATIVE : HEALTHY_CUMULATIVE;
            double mean = service.breaching ? BREACH_MEAN_SECONDS : HEALTHY_MEAN_SECONDS;

            service.total += requests;
            service.errors += requests * errorRate;
            service.sumSeconds += requests * mean;
            for (int i = 0; i < BUCKETS.length; i++) {
                service.bucketCounts[i] += requests * cumulative[i];
            }
        }
    }

    /** The configured topology, for seeding {@code sentinel.dependencies}. */
    public Map<String, List<String>> topology() {
        lock.lock();
        try {
            Map<String, List<String>> edges = new LinkedHashMap<>();
            for (Service service : services) {
                edges.put(service.name, List.of());
            }
            // Each chain is a line: depth 0 calls depth 1 calls depth 2 ... The deepest service is
            // the leaf, and breaking it is what cascades upward.
            for (Service service : services) {
                int depth = depthOf(service.name);
                String callee = nameOf(service.chain, depth + 1);
                if (edges.containsKey(callee)) {
                    edges.put(service.name, List.of(callee));
                }
            }
            return edges;
        } finally {
            lock.unlock();
        }
    }

    public int serviceCount() {
        return services.size();
    }

    public List<String> serviceNames() {
        return services.stream().map(s -> s.name).toList();
    }

    /** {@code synth-c000-s0}: chain, then depth within it. Zero padded so sorting matches order. */
    private static String nameOf(int chain, int depth) {
        return "synth-c%03d-s%d".formatted(chain, depth);
    }

    private static int depthOf(String name) {
        return Integer.parseInt(name.substring(name.lastIndexOf('s') + 1));
    }

    /**
     * The bucket label, formatted exactly as the platform formats it.
     *
     * <p>{@code PromQlTemplates.bucketLabel} is {@code Double.toString(ms / 1000.0)}, so a 500ms SLO
     * selects {@code le="0.5"}. Any other rendering here — {@code 0.50}, {@code .5} — is a label
     * that never matches, and a query that returns an empty vector rather than an error.
     */
    private static String formatLe(double bucket) {
        return Double.toString(bucket);
    }

    private static String format(double value) {
        return "%.0f".formatted(Math.max(0.0, value));
    }
}
