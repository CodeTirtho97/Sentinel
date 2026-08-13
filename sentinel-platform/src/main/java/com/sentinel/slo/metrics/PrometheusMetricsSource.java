package com.sentinel.slo.metrics;

import com.sentinel.config.SentinelProperties;
import com.sentinel.slo.domain.SloType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PrometheusMetricsSource implements MetricsSource {

    private static final Logger log = LoggerFactory.getLogger(PrometheusMetricsSource.class);

    private final PrometheusQueryClient queries;
    private final SentinelProperties props;
    private final Clock clock;

    // Query timing and failures are instrumented in PrometheusQueryClient, which is where the HTTP
    // call actually happens. Instrumenting here as well would double-count, and Prometheus rejects
    // a second registration of the same meter name under a different tag key outright.
    PrometheusMetricsSource(PrometheusQueryClient queries, SentinelProperties props, Clock clock) {
        this.queries = queries;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public Map<String, ErrorRatio> errorRatios(SloType type, Integer latencyThresholdMs, Duration window) {
        Instant asOf = clock.instant();
        Map<String, Double> ratios;
        Map<String, Double> requests;
        Map<String, Double> samples;
        try {
            ratios = queries.queryVector(PromQlTemplates.ratio(type, latencyThresholdMs, window));
            requests = queries.queryVector(PromQlTemplates.requests(window));
            samples = queries.queryVector(PromQlTemplates.samples(window));
        } catch (RuntimeException e) {
            // Retries are already exhausted. An unavailable backend must never look like a breach.
            log.warn("metrics unavailable for {} over {}: {}", type, window, e.toString());
            return Map.of();
        }

        double expectedSamples = expectedSamples(window);
        Map<String, ErrorRatio> out = new HashMap<>(ratios.size());
        ratios.forEach((service, ratio) -> {
            long total = Math.round(requests.getOrDefault(service, 0.0));
            double coverage = expectedSamples <= 0
                    ? 0.0
                    : Math.clamp(samples.getOrDefault(service, 0.0) / expectedSamples, 0.0, 1.0);
            out.put(service, new ErrorRatio(ratio, total, coverage, asOf));
        });
        return out;
    }

    @Override
    public Optional<BudgetCounts> budgetCounts(String serviceName, Duration rollingWindow) {
        try {
            Map<String, Double> errors = queries.queryVector(PromQlTemplates.budgetErrors(rollingWindow));
            Map<String, Double> total = queries.queryVector(PromQlTemplates.budgetTotal(rollingWindow));
            Double totalValue = total.get(serviceName);
            if (totalValue == null) {
                return Optional.empty();
            }
            return Optional.of(new BudgetCounts(
                    Math.round(errors.getOrDefault(serviceName, 0.0)), Math.round(totalValue), clock.instant()));
        } catch (RuntimeException e) {
            log.warn("budget counts unavailable for {}: {}", serviceName, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public boolean isHealthy() {
        return queries.isHealthy();
    }

    /** How many scrapes a fully populated window should contain. */
    private double expectedSamples(Duration window) {
        long scrapeSeconds = props.getMetrics().getScrapeInterval().toSeconds();
        return scrapeSeconds <= 0 ? 0.0 : (double) window.toSeconds() / scrapeSeconds;
    }
}
